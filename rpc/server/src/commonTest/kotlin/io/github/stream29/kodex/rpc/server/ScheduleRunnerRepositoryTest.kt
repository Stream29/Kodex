package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.utils.mq.InMemoryMqBroker

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal val scheduleRunnerRepositoryTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("a failed due-row decode retries the real repository and delivers once after repair") {
        withTimeout(15.seconds) {
            val home = Path(SystemTemporaryDirectory, "kodex-schedule-retry-${Random.nextLong()}")
            val due = Instant.parse("2026-11-18T00:00:00Z")
            val observeAttempts = MutableStateFlow(false)
            val dueReads = MutableStateFlow(0)
            val clock = object : Clock {
                override fun now(): Instant {
                    if (observeAttempts.value) dueReads.update { it + 1 }
                    return due
                }
            }
            try {
                val client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                                role = MessageRole.Assistant,
                                content = listOf(ContentItem.OutputText("done")),
                            )),
                            ResponsesStreamEvent.Completed(Response(id = "schedule-delivered", endTurn = true)),
                        )
                    }
                }
                testKodexAgentDependencies(client, clock).use { dependencies ->
                    val broker = InMemoryMqBroker()
                    val backend = LazySqliteBackendPersistence(this, home, dependencies, {}, { 20.seconds }, {}, broker = broker)
                    try {
                        val (sessions, schedules) = backend.acquire()
                        val sessionId = sessions.create()
                        val agentId = sessions.getRootAgentId(sessionId)
                        val session = sessions.open(sessionId)
                        session.runtime.modify { it.initialize(KodexAgentSettings(OpenAiModelId("test-model"))) }
                        val schedule = schedules.create(agentId, due, null, "retry fixture", due)
                        val db = Path(home, "kodex.db")
                        suspend fun setCreatedAt(value: String) = withContext(Dispatchers.IO) {
                            openExistingSqliteConnection(db).use {
                                it.execute("UPDATE schedules SET created_at = ? WHERE schedule_id = ?", listOf(value, schedule.scheduleId))
                                assertEquals(1, it.changes())
                            }
                        }
                        setCreatedAt("malformed fixture timestamp")
                        observeAttempts.value = true
                        backend.startScheduleRunner(AgentEventBus(backend.sessionRepository, broker))
                        // Each attempt reads the clock to wake and select due rows.
                        // The third read is already a second attempt, before we repair the row.
                        dueReads.first { it >= 3 }
                        assertFalse(backend.runnerFailure.isCompleted)
                        observeAttempts.value = false
                        setCreatedAt(due.toString())
                        while (schedules.list(agentId).isNotEmpty()) delay(20)
                        session.runtime.state.first { it == KodexAgentStateValue.AssistantMessage }
                        val persisted = withContext(Dispatchers.IO) {
                            openExistingSqliteConnection(db).use {
                                it.query("SELECT COUNT(*) FROM agent_commits WHERE agent_id = ? AND type = 'event_schedule'",
                                    listOf(agentId.toLong())).single().single()
                            }
                        }
                        assertEquals(1L, persisted)
                        assertFalse(backend.runnerFailure.isCompleted)
                    } finally {
                        backend.closeAndDrain()
                    }
                }
            } finally {
                withContext(NonCancellable) { removeScheduleFixture(home) }
            }
        }
    }
}

private suspend fun removeScheduleFixture(path: Path) {
    val fs = SystemCoroutineFileSystem
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { removeScheduleFixture(it) }
    fs.delete(path)
}
