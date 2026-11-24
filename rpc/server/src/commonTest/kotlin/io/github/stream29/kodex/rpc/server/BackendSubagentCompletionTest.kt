package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.utils.rpcexception.AgentCompleted
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.SubagentCompletionNoticeFailed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.rpc.withService
import kotlin.test.*

val backendSubagentCompletionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("completion while both Agents run queues one notice but does not stop either turn") {
        val parentEntered = CompletableDeferred<Unit>()
        val releaseParent = CompletableDeferred<Unit>()
        val returnAfterNotice = CompletableDeferred<Unit>()
        val childEntered = CompletableDeferred<Unit>()
        val completedNotice = CompletableDeferred<String>()
        val client = mockOpenAiClient {
            createResponse { request ->
                val texts = request.input.filterIsInstance<ResponseItem.Message>()
                    .flatMap { it.content }.filterIsInstance<ContentItem.InputText>().map { it.text }
                when {
                    "child task" in texts -> flow { childEntered.complete(Unit); awaitCancellation() }
                    texts.any { it.startsWith("Subagent #") && it.endsWith(" completed") } -> flow {
                        completedNotice.complete(texts.single { it.startsWith("Subagent #") })
                        returnAfterNotice.await()
                        emitAll(finalResponse())
                    }
                    else -> flow {
                        parentEntered.complete(Unit)
                        releaseParent.await()
                        emitAll(finalResponse())
                    }
                }
            }
        }
        runtimeFixture(client) { repository, rootId, _ ->
            val repo = repository as SqliteKodexSessionRepository
            val parent = checkNotNull(repo.openAgent(rootId))
            val (childId, child) = repo.initializedChild(rootId)
            val backend = BackendAgentRuntimeRpc(repo, markChildCompleted = repo::markChildCompleted)
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                rpc.submit(rootId, listOf(ContentItem.InputText("parent task")))
                rpc.submit(childId, listOf(ContentItem.InputText("child task")))
                parentEntered.await()
                childEntered.await()
                val parentTurn = assertNotNull(parent.runtime.runningTurn.value)
                val childTurn = assertNotNull(child.runtime.runningTurn.value)
                assertFailsWith<AgentNotActive> { rpc.completeAgent(childId, "old-invocation") }
                rpc.completeAgent(childId, child.runtime.invocationId)
                rpc.completeAgent(childId)
                assertTrue(repo.getAgentRecord(childId)?.completed == true)
                assertSame(parentTurn, parent.runtime.runningTurn.value)
                assertSame(childTurn, child.runtime.runningTurn.value)
                assertFalse(repo.getEntry(parent.sessionId).archived)
                rpc.completeAgent(rootId)
                assertSame(parentTurn, parent.runtime.runningTurn.value)
                assertSame(childTurn, child.runtime.runningTurn.value)
                assertTrue(repo.getEntry(parent.sessionId).archived)
                // The parent is held at releaseParent; observe without entering a state mutation.
                val storage = parent.storage
                val queued = storage.getSteerAt(storage.latestIndex()).filterIsInstance<StableSubagentCompletedEvent>()
                val committed = storage.getFirstCommitsIn(0..Int.MAX_VALUE, CompletionTypes, 2).map { it.commit }
                assertEquals(listOf(StableSubagentCompletedEvent(childId)), queued + committed)
                assertFailsWith<AgentCompleted> { rpc.submit(childId, listOf(ContentItem.InputText("not accepted"))) }
                assertFailsWith<AgentCompleted> { rpc.requestResume(childId) }
                assertFailsWith<AgentCompleted> { rpc.resume(childId) }
                releaseParent.complete(Unit)
                assertEquals("Subagent #$childId completed", completedNotice.await())
                assertSame(parentTurn, parent.runtime.runningTurn.value)
                checkCompletion(parent, childId)
                val nonce = rpc.getCacheNonce(rootId)
                val at = rpc.getLatestIndex(rootId)
                val notice = rpc.getFirstCommitsIn(rootId, nonce, 0, at, AgentCommitType.IndexTypes)
                    .single { it.commit is StableSubagentCompletedEvent }
                assertEquals(StableSubagentCompletedEvent(childId), notice.commit)
                assertTrue(rpc.getFirstCommitsIn(rootId, nonce, notice.index, notice.index, AgentCommitType.WorkTypes).isEmpty())
                returnAfterNotice.complete(Unit)
                rpc.cancelRunningTurn(childId)
                rpc.getRunningTurnFlow(childId).first { !it }
            }
        }
    }

    for (parkParent in listOf(false, true)) {
        test("completion reaches only the ${if (parkParent) "parked" else "idle"} direct parent") {
            val notice = CompletableDeferred<String>()
            val client = mockOpenAiClient {
                createResponse { request ->
                    val texts = request.input.filterIsInstance<ResponseItem.Message>()
                        .flatMap { it.content }.filterIsInstance<ContentItem.InputText>().map { it.text }
                    val completed = texts.singleOrNull { it.startsWith("Subagent #") && it.endsWith(" completed") }
                    when {
                        completed != null -> { notice.complete(completed); finalResponse() }
                        parkParent -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                                name = "await_event", callId = "await_completion_fixture",
                                arguments = """{"name":"Wait for a user message","events":[{"type":"user_message"}]}""",
                            )),
                            ResponsesStreamEvent.Completed(Response(id = "park", endTurn = false)),
                        )
                        else -> finalResponse()
                    }
                }
            }
            runtimeFixture(client) { repository, rootId, _ ->
                val repo = repository as SqliteKodexSessionRepository
                val (parentId, parent) = repo.initializedChild(rootId)
                val (childId, _) = repo.initializedChild(parentId)
                val rpc = BackendAgentRuntimeRpc(repo, markChildCompleted = repo::markChildCompleted)
                parent.runtime.appendUserMessage(listOf(ContentItem.InputText("initial work")))
                rpc.resume(parentId)
                assertFalse(rpc.getRunningTurn(parentId))
                if (parkParent) assertIs<AgentStateValue.ToolPending>(rpc.getState(parentId))
                else assertEquals(AgentStateValue.AssistantMessage, rpc.getState(parentId))
                rpc.completeAgent(childId)
                assertEquals("Subagent #$childId completed", notice.await())
                checkCompletion(parent, childId)
                val root = checkNotNull(repo.openAgent(rootId))
                assertEquals(0, root.storage.countCommitsIn(0..Int.MAX_VALUE, CompletionTypes))
                assertTrue(root.storage.getSteerAt(root.runtime.latestIndex.value).isEmpty())
            }
        }
    }

    test("a failed parent notification leaves a completed child and is not replayed") {
        runtimeFixture { repository, rootId, _ ->
            val repo = repository as SqliteKodexSessionRepository
            val (childId, _) = repo.initializedChild(rootId)
            var attempts = 0
            val eventBus = object : AgentEventBus by AgentEventBus(repo) {
                override suspend fun submitSubagentCompletion(parentAgentId: Int, childAgentId: Int) {
                    assertEquals(rootId, parentAgentId)
                    assertEquals(childId, childAgentId)
                    attempts++
                    error("Fixture parent write failure")
                }
            }
            val backend = BackendAgentRuntimeRpc(repo, eventBus, markChildCompleted = repo::markChildCompleted)
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                assertFailsWith<SubagentCompletionNoticeFailed> { rpc.completeAgent(childId) }
                assertTrue(repo.getAgentRecord(childId)?.completed == true)
                rpc.completeAgent(childId)
            }
            assertEquals(1, attempts)
        }
    }
}

private val CompletionTypes = setOf(AgentCommitType.EventSubagentCompleted)

private suspend fun SqliteKodexSessionRepository.initializedChild(parentId: Int): Pair<Int, KodexAgentSession> {
    val id = createChild(parentId).agentId
    val owner = checkNotNull(openAgent(id))
    owner.runtime.modify { it.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"))) }
    return id to owner
}

private suspend fun checkCompletion(owner: KodexAgentSession, childId: Int) {
    val event = owner.storage.getFirstCommitsIn(0..Int.MAX_VALUE, CompletionTypes, 2).single()
    assertEquals(StableSubagentCompletedEvent(childId), event.commit)
    assertTrue(event.index > 0)
}

private fun finalResponse() = flowOf(
    ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        id = ResponseItemId("fixture_message"), role = MessageRole.Assistant,
        phase = MessagePhase.FinalAnswer, content = listOf(ContentItem.OutputText("fixture answer")),
    )),
    ResponsesStreamEvent.Completed(Response(id = "fixture_response", endTurn = true)),
)
