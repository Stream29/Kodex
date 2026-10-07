package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstate.tool.visibleToolSpecs
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.codexRequestWindowId
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

val compactionSettingsConsistencyTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        for (forced in listOf(false, true)) {
            test("latest full settings survive multiple writes with forced=$forced and request stays fixed") {
                val storage = InMemoryKodexAgentStorage(
                    KodexAgentSettings(OpenAiModelId("initial"), threadName = "before"),
                )
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val requests = mutableListOf<ResponsesApiRequest>()
                val agent = KodexAgentState(
                    mockOpenAiClient {
                        createRemoteCompactionV2Response { request ->
                            requests += request
                            entered.complete(Unit)
                            release.await()
                            RemoteCompactionV2Response(
                                ResponseItem.Compaction(encryptedContent = "checkpoint"), null,
                            )
                        }
                    }, storage,
                )
                val content = listOf(ContentItem.InputText("compact this"))
                agent.appendUserMessage(content)
                val snapshotIndex = agent.latestIndex.value
                val initial = storage.settings[snapshotIndex]
                val running = async(start = CoroutineStart.UNDISPATCHED) {
                    if (forced) agent.forcedCompact()
                    else agent.compact(
                        CompactionTrigger.Auto, CompactionReason.ContextLimit,
                        CompactionPhase.PreTurn,
                    )
                }
                try {
                    entered.await()
                    assertEquals(KodexAgentStateValue.Compacting, agent.state.value)
                    val renamed = initial.copy(threadName = "automatic title")
                    assertTrue(agent.compareAndSetSettings(initial.copy(), renamed))
                    assertEquals(snapshotIndex + 1, agent.latestIndex.value)
                    val selected = renamed.copy(
                        model = OpenAiModelId("selected"),
                        serviceTier = ServiceTier.Fast,
                        reasoning = Reasoning(effort = ReasoningEffort.High),
                        requestUserInputMode = RequestUserInputMode.NoQuestion,
                        cwd = Path("different-cwd"),
                        instructions = "next request only",
                        autoCompactionTokenLimit = 321L,
                        turnState = "latest-routing",
                    )
                    assertEquals(snapshotIndex + 2, agent.updateSettings(selected))
                    val latest = selected.copy(threadName = "manual title", serviceTier = ServiceTier.Flex)
                    assertTrue(agent.compareAndSetSettings(selected, latest))
                    val acceptedIndex = agent.latestIndex.value
                    assertTrue(agent.compareAndSetSettings(latest.copy(), latest.copy()))
                    assertFalse(agent.compareAndSetSettings(initial, initial.copy(threadName = "stale")))
                    assertEquals(acceptedIndex, agent.latestIndex.value)
                    assertEquals(KodexAgentStateValue.Compacting, agent.state.value)
                    assertEquals(initial, storage.settings[snapshotIndex])
                    assertEquals(latest, storage.settings.getExact(acceptedIndex))
                    release.complete(Unit)
                    val checkpoint = running.await()

                    assertEquals(acceptedIndex + 2, checkpoint)
                    assertEquals(storage.latestIndex(), agent.latestIndex.value)
                    val committed = storage.settings[checkpoint]
                    assertNotEquals(initial.windowId, committed.windowId)
                    assertEquals(latest.copy(
                        windowNumber = latest.windowNumber + 1,
                        previousWindowId = latest.windowId,
                        windowId = committed.windowId,
                    ), committed)
                    assertEquals(listOf(snapshotIndex + 1, snapshotIndex + 2, acceptedIndex, checkpoint - 1),
                        storage.settings.indexesIn((snapshotIndex + 1)..checkpoint))
                    assertEquals(listOf(snapshotIndex + 1, snapshotIndex + 2, acceptedIndex, checkpoint),
                        storage.timestamp.indexesIn((snapshotIndex + 1)..checkpoint))
                    assertIs<CleanCompactionPoint>(storage.index.getExact(checkpoint - 1))
                    assertIs<StableContextCompaction>(storage.work.getExact(checkpoint))
                    assertEquals(0L, storage.tokenCount[checkpoint].totalTokens)
                    assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                    val request = requests.single()
                    assertEquals(initial.model, request.model)
                    assertEquals(initial.reasoning, request.reasoning)
                    assertEquals(initial.serviceTier, request.serviceTier)
                    assertEquals(initial.instructions, request.instructions)
                    assertEquals(TestMcpService.visibleToolSpecs(initial), request.tools)
                    assertEquals(initial.codexRequestWindowId(storage.uri.toCodexThreadId()),
                        request.clientMetadata?.windowId)
                    assertEquals(initial.turnId, request.clientMetadata?.turnId)
                    assertEquals(listOf(
                        ResponseItem.Message(role = MessageRole.User, content = content),
                        ResponseItem.CompactionTrigger,
                    ), request.input)
                    // A write after the checkpoint is a new transition, not another checkpoint.
                    val after = committed.copy(threadName = "after")
                    assertTrue(agent.compareAndSetSettings(committed, after))
                    assertEquals(checkpoint + 1, agent.latestIndex.value)
                    assertEquals(latest, storage.settings.getExact(acceptedIndex))
                    assertEquals(committed, storage.settings.getExact(checkpoint - 1))
                } finally {
                    release.complete(Unit)
                    running.cancelAndJoin()
                }
            }
        }

        for (cancelled in listOf(false, true)) {
            test("remote ${if (cancelled) "cancellation" else "failure"} preserves accepted settings without checkpoint") {
                val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("initial")))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val failure = IllegalStateException("remote failure")
                val agent = KodexAgentState(mockOpenAiClient {
                    createRemoteCompactionV2Response {
                        entered.complete(Unit)
                        release.await()
                        throw failure
                    }
                }, storage)
                agent.appendUserMessage(listOf(ContentItem.InputText("compact")))
                val before = storage.settings[agent.latestIndex.value]
                // Catch within the child so an injected failure cannot cancel the test owner.
                val running = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { agent.forcedCompact() }.exceptionOrNull()
                }
                try {
                    entered.await()
                    val latest = before.copy(threadName = "accepted", model = OpenAiModelId("latest"))
                    assertTrue(agent.compareAndSetSettings(before, latest))
                    val acceptedIndex = agent.latestIndex.value
                    if (cancelled) {
                        running.cancel()
                        assertFailsWith<CancellationException> { running.await() }
                        running.join()
                    } else {
                        release.complete(Unit)
                        assertSame(failure, running.await())
                    }
                    assertEquals(acceptedIndex, storage.latestIndex())
                    assertEquals(acceptedIndex, agent.latestIndex.value)
                    assertEquals(latest, storage.settings[acceptedIndex])
                    assertEquals(emptyList(), storage.work.indexesIn(0..Int.MAX_VALUE))
                    assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                    assertTrue(agent.compareAndSetSettings(latest, latest.copy(threadName = "next")))
                } finally {
                    release.complete(Unit)
                    running.cancelAndJoin()
                }
            }
        }
    }
}
