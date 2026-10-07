package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

val agentStateHardCutoverTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("factory loading failure cancels its child and preserves borrowed resources") {
            val backing = InMemoryKodexAgentStorage(
                KodexAgentSettings(OpenAiModelId("test")),
            )
            val failure = IllegalStateException("injected latest read failure")
            val storage = object : MutableKodexAgentStorage by backing {
                override val settings =
                    object : MutableIndexVersioned<KodexAgentSettings> by backing.settings {
                        override suspend fun latestIndex(): Int = throw failure
                    }
            }
            var clientClosed = false
            val client = object : OpenAiClient by mockOpenAiClient() {
                override fun close() { clientClosed = true }
            }
            var mcpClosed = false
            val mcp = object : McpService by TestMcpService {
                override fun close() { mcpClosed = true }
            }
            val owner = supervisorChildScope()
            try {
                assertSame(failure, assertFailsWith<IllegalStateException> {
                    owner.KodexAgentState(
                        client, storage, TestAgentContextSettings, mcp,
                    )
                })
                assertTrue(owner.coroutineContext.job.isActive)
                assertTrue(owner.coroutineContext.job.children.none { it.isActive })
                assertFalse(clientClosed)
                assertFalse(mcpClosed)
                // Reuse the same owner and borrowed resources after failed construction.
                val agent = owner.KodexAgentState(
                    client, backing, TestAgentContextSettings, mcp,
                )
                agent.cancelAndJoin()
                assertFalse(clientClosed)
                assertFalse(mcpClosed)
            } finally {
                owner.cancelAndJoin()
            }
        }

        test("injected user messages use final versus commentary turn boundaries") {
            for (phase in listOf(MessagePhase.FinalAnswer, MessagePhase.Commentary)) {
                val storage = InMemoryKodexAgentStorage(
                    KodexAgentSettings(OpenAiModelId("test")),
                )
                val agent = KodexAgentState(mockOpenAiClient(), storage)
                agent.appendUserMessage(listOf(ContentItem.InputText("first")))
                val initialTurn = storage.settings[agent.latestIndex.value].turnId
                agent.modify {
                    it.index[2] = StableAssistantMessage(
                        content = listOf(ContentItem.OutputText("answer")),
                        phase = phase,
                    )
                }
                agent.injectHistory(
                    listOf(
                        StableUserMessage(listOf(ContentItem.InputText("second"))),
                        StableUserMessage(listOf(ContentItem.InputText("third"))),
                    ),
                )
                val nextTurn = storage.settings[3].turnId
                if (phase == MessagePhase.FinalAnswer) assertNotEquals(initialTurn, nextTurn)
                else assertEquals(initialTurn, nextTurn)
                assertEquals(nextTurn, storage.settings[4].turnId)
                assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
            }
        }

        test("compaction checkpoint preserves settings accepted during its wait") {
            val storage = InMemoryKodexAgentStorage(
                KodexAgentSettings(OpenAiModelId("test"), threadName = "before"),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val agent = KodexAgentState(
                mockOpenAiClient {
                    createRemoteCompactionV2Response {
                        entered.complete(Unit)
                        release.await()
                        RemoteCompactionV2Response(
                            ResponseItem.Compaction(encryptedContent = "checkpoint"), null,
                        )
                    }
                },
                storage,
            )
            agent.appendUserMessage(listOf(ContentItem.InputText("compact")))
            val compaction = async(start = CoroutineStart.UNDISPATCHED) {
                agent.forcedCompact()
            }
            try {
                entered.await()
                val current = storage.settings[agent.latestIndex.value]
                assertTrue(agent.compareAndSetSettings(current, current.copy(threadName = "during")))
                val acceptedIndex = agent.latestIndex.value
                assertEquals("during", storage.settings[acceptedIndex].threadName)
                release.complete(Unit)
                val checkpointIndex = compaction.await()
                assertTrue(checkpointIndex > acceptedIndex)
                assertEquals("during", storage.settings[checkpointIndex].threadName)
                assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
            } finally {
                release.complete(Unit)
                compaction.cancelAndJoin()
            }
        }
    }
}
