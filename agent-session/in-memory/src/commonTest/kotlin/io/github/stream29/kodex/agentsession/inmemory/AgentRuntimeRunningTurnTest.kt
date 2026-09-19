package io.github.stream29.kodex.agentsession.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstate.impl.KodexAgentStateInvalidTransitionException
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin as cancelJobAndJoin

val agentRuntimeRunningTurnTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("manual compaction publishes its caller and returns the original checkpoint") {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val metadata = mutableListOf<String>()
            val root = openTurnTestSession(
                mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, turnMetadata, _ ->
                        metadata += turnMetadata
                        entered.complete(Unit)
                        finish.await()
                        compactedResponse()
                    }
                },
            )
            val runtime = root.runtime
            runtime.appendUserMessage(listOf(ContentItem.InputText("Compact this.")))
            val before = root.storage.settings[runtime.latestIndex.value]
            val queued = listOf(StableUserMessage(listOf(ContentItem.InputText("Keep queued."))))
            runtime.pendingSteer.value = queued

            val turn = async(start = CoroutineStart.UNDISPATCHED) { runtime.forcedCompact() }
            try {
                entered.await()
                assertSame(turn, runtime.runningTurn.value)
                assertEquals(KodexAgentStateValue.Compacting, runtime.state.value)
                finish.complete(Unit)
                val index = turn.await()

                assertEquals(index, runtime.latestIndex.value)
                assertEquals(index, root.storage.latestIndex())
                assertEquals(StableContextCompaction(encryptedContent = "compacted"), root.storage.work[index])
                assertEquals(0L, root.storage.tokenCount[index])
                val after = root.storage.settings[index]
                assertEquals(before.turnId, after.turnId)
                assertEquals(before.windowNumber + 1, after.windowNumber)
                assertTrue(metadata.single().contains("\"trigger\":\"manual\""))
                assertTrue(metadata.single().contains("\"reason\":\"user_requested\""))
                assertTrue(metadata.single().contains("\"phase\":\"standalone_turn\""))
                assertEquals(queued, runtime.pendingSteer.value)
                assertNull(runtime.runningTurn.value)
            } finally {
                finish.complete(Unit)
                turn.cancelJobAndJoin()
            }
        }

        test("manual compaction rejects both a second compaction and resume") {
            val entered = CompletableDeferred<Unit>()
            val root = openTurnTestSession(
                mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                },
            )
            val runtime = root.runtime
            runtime.appendUserMessage(listOf(ContentItem.InputText("Compact this.")))
            val turn = async(start = CoroutineStart.UNDISPATCHED) { runtime.forcedCompact() }
            try {
                entered.await()
                assertSame(turn, runtime.runningTurn.value)
                assertFailsWith<ConcurrentAgentRuntimeResumeException> { runtime.forcedCompact() }
                assertFailsWith<ConcurrentAgentRuntimeResumeException> { runtime.resume() }
                assertSame(turn, runtime.runningTurn.value)
            } finally {
                turn.cancelJobAndJoin()
            }
            assertNull(runtime.runningTurn.value)
            assertEquals(KodexAgentStateValue.UserMessage, runtime.state.value)
        }

        test("resume rejects an explicit compaction without losing its own slot") {
            val entered = CompletableDeferred<Unit>()
            val root = openTurnTestSession(
                mockOpenAiClient {
                    createResponse {
                        flow {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                },
            )
            val runtime = root.runtime
            runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))
            val turn = async(start = CoroutineStart.UNDISPATCHED) { runtime.resume() }
            try {
                entered.await()
                assertFailsWith<ConcurrentAgentRuntimeResumeException> { runtime.forcedCompact() }
                assertSame(turn, runtime.runningTurn.value)
            } finally {
                turn.cancelJobAndJoin()
            }
            assertNull(runtime.runningTurn.value)
        }

        test("failed compaction preserves the failure and permits a later operation") {
            val failure = IllegalStateException("test compaction failure")
            var calls = 0
            val root = openTurnTestSession(
                mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        if (++calls == 1) throw failure
                        compactedResponse()
                    }
                },
            )
            val runtime = root.runtime
            val originalIndex = runtime.appendUserMessage(listOf(ContentItem.InputText("Compact this.")))
            assertSame(failure, assertFailsWith<IllegalStateException> { runtime.forcedCompact() })
            assertNull(runtime.runningTurn.value)
            assertEquals(originalIndex, runtime.latestIndex.value)
            assertEquals(KodexAgentStateValue.UserMessage, runtime.state.value)

            assertTrue(runtime.forcedCompact() > originalIndex)
            assertEquals(2, calls)
            assertNull(runtime.runningTurn.value)
        }

        test("state admission failure releases the runtime slot without calling the provider") {
            val root = openTurnTestSession(mockOpenAiClient())
            assertEquals(KodexAgentStateValue.Empty, root.runtime.state.value)
            assertFailsWith<KodexAgentStateInvalidTransitionException> { root.runtime.forcedCompact() }
            assertNull(root.runtime.runningTurn.value)
            assertEquals(KodexAgentStateValue.Empty, root.runtime.state.value)
        }

        test("cancelling compaction keeps the slot through cleanup and preserves queued steer") {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            val root = openTurnTestSession(
                mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                finishCleanup.await()
                            }
                        }
                    }
                },
            )
            val runtime = root.runtime
            val originalIndex = runtime.appendUserMessage(listOf(ContentItem.InputText("Compact this.")))
            val queued = listOf(StableUserMessage(listOf(ContentItem.InputText("Still queued."))))
            runtime.pendingSteer.value = queued
            val turn = async(start = CoroutineStart.UNDISPATCHED) { runtime.forcedCompact() }
            try {
                entered.await()
                runtime.runningTurn.value!!.cancel()
                cleaning.await()
                assertFalse(turn.isCompleted)
                assertSame(turn, runtime.runningTurn.value)
                assertEquals(KodexAgentStateValue.Compacting, runtime.state.value)
                assertFailsWith<ConcurrentAgentRuntimeResumeException> { runtime.resume() }
            } finally {
                finishCleanup.complete(Unit)
                turn.cancelJobAndJoin()
            }
            assertTrue(turn.isCancelled)
            assertNull(runtime.runningTurn.value)
            assertEquals(originalIndex, runtime.latestIndex.value)
            assertEquals(KodexAgentStateValue.UserMessage, runtime.state.value)
            assertEquals(queued, runtime.pendingSteer.value)
        }

        for (preTurn in listOf(true, false)) {
            val phase = if (preTurn) "pre_turn" else "mid_turn"
            test("automatic $phase compaction remains inside the original resume slot") {
                val entered = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val metadata = mutableListOf<String>()
                var responses = 0
                val root = openTurnTestSession(
                    mockOpenAiClient {
                        createRemoteCompactionV2Response { _, _, turnMetadata, _ ->
                            metadata += turnMetadata
                            entered.complete(Unit)
                            finish.await()
                            compactedResponse()
                        }
                        createResponse {
                            responses++
                            flowOf(
                                ResponsesStreamEvent.Completed(
                                    Response(
                                        id = "response_$responses",
                                        endTurn = preTurn || responses > 1,
                                        usage = TokenUsage(80, 10, 90),
                                    ),
                                ),
                            )
                        }
                    },
                    KodexAgentSettings(OpenAiModelId("test-model"), autoCompactionTokenLimit = 20),
                )
                val runtime = root.runtime
                val index = runtime.appendUserMessage(listOf(ContentItem.InputText("Continue.")))
                runtime.modify { it.tokenCount[index] = if (preTurn) 90L else 1L }
                val turn = async(start = CoroutineStart.UNDISPATCHED) { runtime.resume() }
                try {
                    entered.await()
                    assertSame(turn, runtime.runningTurn.value)
                    assertEquals(KodexAgentStateValue.Compacting, runtime.state.value)
                    finish.complete(Unit)
                    turn.await()
                    assertNull(runtime.runningTurn.value)
                    assertTrue(metadata.single().contains("\"trigger\":\"auto\""))
                    assertTrue(metadata.single().contains("\"reason\":\"context_limit\""))
                    assertTrue(metadata.single().contains("\"phase\":\"$phase\""))
                    assertEquals(if (preTurn) 1 else 2, responses)
                } finally {
                    finish.complete(Unit)
                    turn.cancelJobAndJoin()
                }
            }
        }
    }
}

private suspend fun CoroutineScope.openTurnTestSession(
    client: OpenAiClient,
    settings: KodexAgentSettings = KodexAgentSettings(OpenAiModelId("test-model")),
): KodexAgentSession {
    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
    return repository.open(repository.create()).also { session ->
        session.runtime.modify { it.initialize(settings) }
    }
}

private fun compactedResponse(): RemoteCompactionV2Response =
    RemoteCompactionV2Response(
        compactionOutput = ResponseItem.Compaction(encryptedContent = "compacted"),
        completedResponse = null,
    )
