package io.github.stream29.codex.lite.agentsession.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentsession.multiagent.AgentPathResolverImpl
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableAgentDeliveryResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMultiAgentOperation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMultiAgentToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnForkMode
import io.github.stream29.codex.lite.tool.multiagent.followupTaskTool
import io.github.stream29.codex.lite.tool.multiagent.sendMessageTool
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin as cancelJobAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun settings(name: String = ""): CodexAgentSettings =
    CodexAgentSettings(model = OpenAiModelId("test-model"), threadName = name)

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )

private suspend fun CodexAgentSession.spawnInitialized(name: String): CodexAgentSession =
    subagents.open(subagents.create()).also { child ->
        child.runtime.modify { target -> storage.forkTo(1, target) }
        child.runtime.updateSettings(settings(name))
    }

private suspend fun CodexSessionRepository.createInitialized(
    settings: CodexAgentSettings,
): Int {
    val index = create()
    open(index).runtime.modify { storage ->
        storage.initialize(settings.copy(threadName = settings.threadName.ifEmpty { "Session $index" }))
    }
    return index
}

val inMemoryCodexSessionRepositoryTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("creates an uninitialized root storage") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val index = repository.create()
        val session = repository.open(index)

        assertEquals(-1, session.storage.latestIndex())
        session.runtime.modify { storage -> storage.initialize(settings("root")) }
        assertEquals(0, session.storage.latestIndex())
        assertEquals(0L, session.storage.tokenCount[0])
    }

    test("creates zero-based root entries") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val first = repository.createInitialized(settings())
        val second = repository.createInitialized(settings("Named"))

        assertEquals(0, first)
        assertEquals(1, second)
        assertEquals("Session 0", repository.open(first).storage.settings[0].threadName)
        assertEquals(listOf(first, second), repository.list())
    }

    test("returns one cached root instance and persists its recursive tree") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val index = repository.createInitialized(settings())
        val root = repository.open(index)
        val first = root.spawnInitialized("first")
        val second = root.spawnInitialized("second")
        val nested = first.spawnInitialized("nested")

        assertSame(root, repository.open(index))
        assertEquals(listOf(first.storage.id, second.storage.id), root.subagents.list().map { entry -> root.subagents.open(entry).storage.id })
        assertEquals(listOf(nested.storage.id), first.subagents.list().map { entry -> first.subagents.open(entry).storage.id })
    }

    test("each Agent manages its direct entries") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val root = repository.open(repository.createInitialized(settings("root")))
        val first = root.subagents.create()
        val second = root.subagents.create()

        assertEquals(listOf(first, second), root.subagents.list())
        assertEquals(-1, root.subagents.open(first).storage.latestIndex())

        root.subagents.delete(first)

        assertEquals(listOf(second), root.subagents.list())
        assertFailsWith<IllegalArgumentException> { root.subagents.open(first) }
    }

    test("delete invalidates cached nodes and releases the numeric slot") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val index = repository.createInitialized(settings())
        val root = repository.open(index)
        val child = root.spawnInitialized("child")

        repository.delete(index)

        assertFailsWith<IllegalStateException> { root.storage.settings.latestIndex() }
        assertFailsWith<IllegalStateException> { child.storage.settings.latestIndex() }
        assertEquals(0, repository.createInitialized(settings()))
    }

    test("fork is a downstream operation and does not copy descendants") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val sourceIndex = repository.createInitialized(settings("Source"))
        val source = repository.open(sourceIndex)
        source.runtime.injectHistory(listOf(userMessage("copied")))
        source.spawnInitialized("child")

        val targetIndex = repository.create()
        val target = repository.open(targetIndex)
        val latest = target.runtime.modify { storage ->
            source.storage.forkTo(2, storage)
            storage.latestIndex()
        }
        target.runtime.updateSettings(
            target.storage.settings[latest].copy(threadName = "[fork] Source"),
        )

        assertEquals(listOf(1), target.storage.stable.indexes().toList())
        assertEquals(userMessage("copied"), target.storage.stable[1])
        assertEquals("[fork] Source", target.storage.settings[2].threadName)
        assertEquals(emptyList(), target.subagents.list())
    }

    test("owns each runtime for the complete Agent session lifecycle") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val root = repository.open(repository.createInitialized(settings("root")))
        val child = root.spawnInitialized("child")

        assertSame(root.storage, root.runtime.storage)
        assertSame(child.storage, child.runtime.storage)

        root.cancelAndJoin()

        assertFalse(root.runtime.coroutineContext[Job]?.isActive ?: true)
        assertFalse(child.runtime.coroutineContext[Job]?.isActive ?: true)
    }

    test("exposes one Unified Exec client for each Agent runtime") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val root = repository.open(repository.createInitialized(settings("root")))
        val child = root.spawnInitialized("child")

        assertNotSame(root.runtime.unifiedExecToolClient, child.runtime.unifiedExecToolClient)
    }

    test("runtime rejects concurrent resume collectors") {
        val entered = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            createResponse { _, _, _, _ ->
                flow<ResponsesStreamEvent> {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val repository = InMemoryCodexSessionRepository(
            testCodexAgentDependencies(client),
        )
        val root = repository.open(repository.createInitialized(settings("root")))
        root.runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            root.runtime.resume().toList()
        }
        entered.await()
        assertSame(first, root.runtime.runningTurn.value)

        assertFailsWith<ConcurrentAgentRuntimeResumeException> {
            root.runtime.resume().toList()
        }
        assertSame(first, root.runtime.runningTurn.value)

        first.cancelJobAndJoin()
        assertNull(root.runtime.runningTurn.value)
    }

    test("a child tool resolves its caller through the shared path resolver") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val root = repository.open(repository.createInitialized(settings("root")))
        val child = root.spawnInitialized("/root/worker")
        val sendMessageTool = child.runtime.sendMessageTool(AgentPathResolverImpl(root))

        val completed = assertIs<StableMultiAgentToolEvent>(
            sendMessageTool.handle(
                PendingMultiAgentToolEvent(
                    callId = "call_send",
                    operation = PendingMultiAgentInvocation.SendMessage(
                        SendMessageArgs("/root", "Caller is the worker."),
                    ),
                ),
            ),
        )

        assertEquals(
            StableAgentDeliveryResult.Success(""),
            assertIs<StableMultiAgentOperation.SendMessage>(completed.operation).result,
        )
        assertTrue(
            root.runtime.pendingSteer.value.any { input ->
                input is StableCleanEvent.AgentMessage &&
                    input.author == "/root/worker" &&
                    input.recipient == "/root" &&
                    input.containsText("Caller is the worker.")
            },
        )
    }

    test("follow-up steers and resumes an idle Agent directly") {
        val client = mockOpenAiClient {
            createResponse { _, _, _, _ ->
                assistantResponse("Follow-up complete.", "followup_complete")
            }
        }
        val repository = InMemoryCodexSessionRepository(
            testCodexAgentDependencies(client),
        )
        val root = repository.open(repository.createInitialized(settings("root")))
        val child = root.spawnInitialized("/root/worker")
        val followupTool = root.runtime.followupTaskTool(AgentPathResolverImpl(root))

        val completed = assertIs<StableMultiAgentToolEvent>(
            followupTool.handle(
                PendingMultiAgentToolEvent(
                    callId = "call_followup",
                    operation = PendingMultiAgentInvocation.FollowupTask(
                        FollowupTaskArgs("/root/worker", "Continue this task."),
                    ),
                ),
            ),
        )

        assertEquals(
            StableAgentDeliveryResult.Success(""),
            assertIs<StableMultiAgentOperation.FollowupTask>(completed.operation).result,
        )
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10.seconds) {
                child.runtime.state.first { state -> state == CodexAgentStateValue.AssistantMessage }
                root.runtime.pendingSteer.first { inputs ->
                    inputs.any { input ->
                        input is StableCleanEvent.AgentMessage &&
                            input.containsText("Follow-up complete.")
                    }
                }
            }
        }
        assertEquals(1, root.runtime.pendingSteer.value.size)
        assertTrue(
            root.storage.stable.indexes().toList().none { index ->
                root.storage.stable[index] is StableCleanEvent.AgentMessage
            },
        )

        root.runtime.resume().toList()

        assertTrue(
            root.storage.stable.indexes().toList().any { index ->
                (root.storage.stable[index] as? StableCleanEvent.AgentMessage)
                    ?.containsText("Follow-up complete.") == true
            },
        )
    }

    test("session runtime executes Multi-agent calls through ordinary tools") {
        val requestMutex = Mutex()
        var rootWindowId = ""
        var rootRequestCount = 0
        val rootToolNames = mutableSetOf<String>()
        val client = mockOpenAiClient {
            createResponse { request, _, _, windowId ->
                val response = requestMutex.withLock {
                    if (rootWindowId.isEmpty()) rootWindowId = windowId
                    if (windowId != rootWindowId) {
                        assistantResponse("Worker complete.", "worker_complete")
                    } else {
                        rootToolNames += request.toolNames()
                        when (rootRequestCount++) {
                            0 -> spawnResponse()
                            1 -> assistantResponse("Root complete.", "root_complete")
                            else -> error("Unexpected root request $rootRequestCount")
                        }
                    }
                }
                response
            }
        }
        val repository = InMemoryCodexSessionRepository(
            testCodexAgentDependencies(client),
        )
        val root = repository.open(repository.createInitialized(settings("root")))

        root.runtime.appendUserMessage(listOf(ContentItem.InputText("Delegate this task.")))
        root.runtime.resume().toList()

        val child = root.subagents.open(root.subagents.list().single())
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10.seconds) {
                child.runtime.state.first { state -> state == CodexAgentStateValue.AssistantMessage }
                while (
                    root.storage.stable.indexes().toList().none { index ->
                    root.storage.stable[index].containsWorkerCompletion()
                    } && root.runtime.pendingSteer.value.none { input ->
                        input is StableCleanEvent.AgentMessage &&
                            input.containsText("Worker complete.")
                    }
                ) {
                    delay(10)
                }
            }
        }
        assertEquals("/root/worker", child.storage.settings[child.storage.latestIndex()].threadName)
        assertTrue(MultiAgentTools.specs.all { spec -> spec.name in rootToolNames })
        assertTrue(
            root.storage.stable.indexes().toList().any { index ->
                root.storage.stable[index].toResponseHistoryItems().any { item ->
                    item is ResponseItem.FunctionCallOutput &&
                        item.callId == "call_spawn" &&
                        item.output.success == true
                }
            },
        )
    }
    }
}

private fun ResponsesApiRequest.toolNames(): List<String> =
    tools.filterIsInstance<ResponsesApiTool>().map(ResponsesApiTool::name)

private fun StableCleanEvent.containsWorkerCompletion(): Boolean = when (this) {
    is StableCleanEvent.AgentMessage -> content
        .filterIsInstance<AgentMessageInputContent.InputText>()
        .any { content -> "Worker complete." in content.text }

    is StableCleanEvent.UserMessage -> content
        .filterIsInstance<ContentItem.InputText>()
        .any { content -> "Worker complete." in content.text }

    else -> false
}

private fun StableCleanEvent.AgentMessage.containsText(text: String): Boolean =
    content.filterIsInstance<AgentMessageInputContent.InputText>()
        .any { content -> text in content.text }

private fun spawnResponse() = flowOf(
    ResponsesStreamEvent.OutputItemDone(
        outputIndex = 0,
        item = ResponseItem.FunctionCall(
            name = MultiAgentTools.SpawnAgentName,
            arguments = OpenAiJsonCodec.encodeToString(
                SpawnAgentArgs(
                    taskName = "worker",
                    message = "Complete one background turn.",
                    forkTurns = SpawnForkMode.None,
                ),
            ),
            callId = "call_spawn",
        ),
    ),
    ResponsesStreamEvent.Completed(Response(id = "spawn_response", endTurn = false)),
)

private fun assistantResponse(
    text: String,
    responseId: String,
) = flowOf(
    ResponsesStreamEvent.OutputItemDone(
        outputIndex = 0,
        item = ResponseItem.Message(
            role = MessageRole.Assistant,
            content = listOf(ContentItem.OutputText(text)),
        ),
    ),
    ResponsesStreamEvent.Completed(Response(id = responseId, endTurn = true)),
)
