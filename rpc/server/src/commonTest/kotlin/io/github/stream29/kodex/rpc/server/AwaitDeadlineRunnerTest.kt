package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentruntime.contract.submitUserMessage
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val awaitDeadlineRunnerTest by testSuite(
    compartment = { TestCompartment.RealTime },
    testConfig = TestConfig.testScope(isEnabled = false, timeout = 20.seconds),
) {
    test("persisted deadline survives automatic eviction and a backend restart, then wakes exactly once") {
        withTimeout(15.seconds) {
            withAwaitHome { home ->
                var requestCount = 0
                val origin = Instant.parse("2026-11-18T00:00:00Z")
                val wallTime = MutableStateFlow(origin)
                val clock = object : Clock {
                    override fun now(): Instant = wallTime.value
                }
                testKodexAgentDependencies(mockOpenAiClient {
                    createResponse {
                        requestCount++
                        if (requestCount == 1) {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                                    name = "await_event",
                                    callId = "deadline",
                                    arguments = """{"events":[{"type":"timeout","duration_ms":90000}]}""",
                                )),
                                ResponsesStreamEvent.Completed(Response(id = "await", endTurn = false)),
                            )
                        } else {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("done")),
                                )),
                                ResponsesStreamEvent.Completed(Response(id = "finished", endTurn = true)),
                            )
                        }
                    }
                }, clock).use { dependencies ->
                    fun persistence() = LazySqliteBackendPersistence(
                        this, home, dependencies, {}, { 20.seconds }, {}, sessionTtl = 1200.milliseconds,
                    )
                    val first = persistence()
                    val agentId = try {
                        val repository = first.acquire().first
                        first.startAwaitDeadlineRunner()
                        val id = repository.create()
                        val runtime = repository.open(id).runtime
                        runtime.modify { it.initialize(KodexAgentSettings(OpenAiModelId("test-model"))) }
                        runtime.appendUserMessage(listOf(ContentItem.InputText("wait")))
                        runtime.resume()
                        val deadline = repository.pendingAwaitDeadlines.value.getValue(id)
                        assertNull(runtime.runningTurn.value)
                        assertEquals(origin + 90.seconds, deadline.timeoutAt)
                        assertEquals(1, requestCount)

                        repository.openSessions.first { it.isEmpty() }
                        assertEquals(deadline, repository.pendingAwaitDeadlines.value[id])
                        assertEquals(1, requestCount)
                        id
                    } finally {
                        first.closeAndDrain()
                    }

                    wallTime.value = origin + 91.seconds // pass the original deadline while the backend is stopped
                    val second = persistence()
                    try {
                        val repository = second.acquire().first
                        assertTrue(repository.openSessions.value.isEmpty())
                        assertEquals(origin + 90.seconds, repository.pendingAwaitDeadlines.value.getValue(agentId).timeoutAt)
                        second.startAwaitDeadlineRunner()
                        val session = repository.openSessions.first { agentId in it }.getValue(agentId)
                        session.runtime.state.first { it == KodexAgentStateValue.AssistantMessage }
                        assertNull(repository.pendingAwaitDeadlines.value[agentId])
                        val commits = session.storage.getCommitsIn(0..session.storage.latestIndex())
                        val completed = assertIs<StableAwait>(commits.single { it.commit.commitType == AgentCommitType.AwaitEventToolEvent }.commit)
                        assertEquals("deadline", completed.callId)
                        assertIs<StableAwaitEventResult.Output>(completed.result)
                        assertFalse(commits.any { it.commit.commitType == AgentCommitType.EventSchedule })
                        assertEquals(2, requestCount)
                    } finally {
                        second.closeAndDrain()
                    }
                }
            }
        }
    }

    test("earlier direct steer and clearing pending invalidate the original host wake pointer") {
        withTimeout(15.seconds) {
            withAwaitHome { home ->
                var requests = 0
                val wallTime = MutableStateFlow(Instant.fromEpochMilliseconds(0))
                val clock = object : Clock {
                    override fun now(): Instant = wallTime.value
                }
                testKodexAgentDependencies(mockOpenAiClient {
                    createResponse { req ->
                        requests++
                        if (req.input.any { it is ResponseItem.FunctionCallOutput }) {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("finished")),
                                )),
                                ResponsesStreamEvent.Completed(Response(id = "finish", endTurn = true)),
                            )
                        } else {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                                    name = "await_event", callId = "expiry",
                                    arguments = """{"events":[{"type":"timeout","duration_ms":1000}]}""",
                                )),
                                ResponsesStreamEvent.Completed(Response(id = "wait", endTurn = false)),
                            )
                        }
                    }
                }, clock).use { dependencies ->
                    val backend = LazySqliteBackendPersistence(
                        this, home, dependencies, {}, { 20.seconds }, {},
                    )
                    try {
                        val (repository) = backend.acquire()
                        backend.startAwaitDeadlineRunner()
                        val ids = List(2) { repository.create() }
                        val sessions = ids.map { id ->
                            repository.open(id).also { session ->
                                session.runtime.modify { it.initialize(KodexAgentSettings(OpenAiModelId("test-model"))) }
                                session.runtime.appendUserMessage(listOf(ContentItem.InputText("wait")))
                                session.runtime.resume()
                            }
                        }
                        val original = repository.pendingAwaitDeadlines.value
                        val fork = repository.createFork(ids[1])
                        assertNull(repository.pendingAwaitDeadlines.value[fork])
                        repository.getEntry(ids[1]).archive()
                        assertEquals(original.getValue(ids[1]), repository.pendingAwaitDeadlines.value[ids[1]])
                        val steered = sessions[0].runtime
                        steered.submitUserMessage(listOf(ContentItem.InputText("wake before timeout")))
                        steered.state.first { it == KodexAgentStateValue.AssistantMessage }
                        val cleared = sessions[1].runtime
                        cleared.clearPending()
                        assertNull(repository.pendingAwaitDeadlines.value[ids[1]])
                        cleared.modify { it.revert(original.getValue(ids[1]).snapshotIndex + 1) }
                        assertEquals(original.getValue(ids[1]), repository.pendingAwaitDeadlines.value[ids[1]])
                        cleared.clearPending()
                        val before = requests
                        val afterClear = cleared.latestIndex.value
                        assertTrue(repository.pendingAwaitDeadlines.value.isEmpty())
                        assertEquals(3, before)
                        wallTime.value += 2.seconds
                        for ((index, id) in ids.withIndex()) {
                            assertNull(repository.openForAwaitDeadline(id, original.getValue(id)))
                            sessions[index].runtime.requestAwaitDeadlineCheck(original.getValue(id)).join()
                        }
                        assertEquals(before, requests)
                        assertEquals(afterClear, cleared.latestIndex.value)
                        repository.delete(ids[1])
                        val nestedFork = repository.createFork(fork)
                        assertEquals(ids[1], nestedFork)
                        assertNull(repository.pendingAwaitDeadlines.value[nestedFork])
                        assertNull(repository.openForAwaitDeadline(nestedFork, original.getValue(ids[1])))
                    } finally {
                        backend.closeAndDrain()
                    }
                }
            }
        }
    }
}

private suspend fun withAwaitHome(block: suspend (Path) -> Unit) {
    val root = Path(SystemTemporaryDirectory, "kodex-await-deadline-${Random.nextLong()}")
    val fs = SystemCoroutineFileSystem
    suspend fun remove(path: Path) {
        val metadata = fs.metadataOrNull(path) ?: return
        if (metadata.isDirectory) fs.list(path).forEach { remove(it) }
        fs.delete(path)
    }
    try {
        block(root)
    } finally {
        remove(root)
    }
}
