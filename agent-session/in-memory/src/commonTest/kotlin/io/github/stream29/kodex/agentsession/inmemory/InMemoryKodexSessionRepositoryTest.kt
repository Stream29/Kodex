package io.github.stream29.kodex.agentsession.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.multiagent.AgentPathResolverImpl
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentDeliveryResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentOperation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.contract.forkTo
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.multiagent.MultiAgentTools
import io.github.stream29.kodex.tool.multiagent.FollowupTaskArgs
import io.github.stream29.kodex.tool.multiagent.SendMessageArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentArgs
import io.github.stream29.kodex.tool.multiagent.followupTaskTool
import io.github.stream29.kodex.tool.multiagent.sendMessageTool
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun settings(name: String = ""): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = name)

private fun userMessage(): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText("copied")),
    )

private suspend fun KodexAgentSession.spawnInitialized(name: String): KodexAgentSession =
    subagents.open(subagents.create()).also { child ->
        child.runtime.modify { target -> storage.forkTo(1, target) }
        child.runtime.updateSettings(settings(name))
    }

private suspend fun KodexSessionRepository.createInitialized(
    settings: KodexAgentSettings,
): Int {
    val index = create()
    open(index).runtime.modify { storage ->
        storage.initialize(settings.copy(threadName = settings.threadName.ifEmpty { "Session $index" }))
    }
    return index
}

val inMemoryKodexSessionRepositoryTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("creates an uninitialized root storage") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val session = repository.open(index)

            assertEquals(-1, session.storage.latestIndex())
            session.runtime.modify { storage -> storage.initialize(settings("root")) }
            assertEquals(0, session.storage.latestIndex())
            assertEquals(0L, session.storage.tokenCount[0])
        }

        test("creates zero-based root entries") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val first = repository.createInitialized(settings())
            val second = repository.createInitialized(settings("Named"))
            val firstLastActivityAt = Instant.parse("2026-07-31T10:00:00Z")
            val secondLastActivityAt = Instant.parse("2026-07-31T10:05:00Z")
            repository.open(first).storage.timestamp[1] = firstLastActivityAt
            repository.open(second).storage.timestamp[1] = secondLastActivityAt

            assertEquals(0, first)
            assertEquals(1, second)
            assertEquals("Session 0", repository.open(first).storage.settings[0].threadName)
            assertEquals(listOf(first, second), repository.list())
            assertEquals(
                listOf(
                    Triple(first, "Session 0", firstLastActivityAt),
                    Triple(second, "Named", secondLastActivityAt),
                ),
                repository.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.threadName, entry.lastActivityAt)
                },
            )
        }

        test("archives and unarchives root entries without affecting index inventory") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val archivedIndex = repository.createInitialized(settings("archived"))
            val activeIndex = repository.createInitialized(settings("active"))
            val archivedEntry = repository.getEntry(archivedIndex)

            archivedEntry.archive()
            archivedEntry.archive()

            assertEquals(listOf(activeIndex), repository.listEntries(includeArchived = false).map { it.entryIndex })
            val allEntries = repository.listEntries(includeArchived = true)
            assertEquals(listOf(archivedIndex, activeIndex), allEntries.map { it.entryIndex })
            assertEquals(listOf("archived", "active"), allEntries.map { it.threadName })
            assertEquals(listOf(true, false), allEntries.map { it.archived })
            assertEquals(listOf(archivedIndex, activeIndex), repository.list())

            archivedEntry.unarchive()
            archivedEntry.unarchive()

            assertEquals(
                listOf(archivedIndex, activeIndex),
                repository.listEntries(includeArchived = false).map { it.entryIndex },
            )
        }

        test("archived roots remain openable, forkable, deletable, and reusable") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("source"))
            val source = repository.open(sourceIndex)
            repository.listEntries()
                .single { entry -> entry.entryIndex == sourceIndex }
                .archive()

            assertSame(source, repository.open(sourceIndex))

            val forkIndex = repository.create()
            val fork = repository.open(forkIndex)
            fork.runtime.modify { storage -> source.storage.forkTo(1, storage) }
            assertEquals(
                false,
                repository.listEntries(includeArchived = true)
                    .single { entry -> entry.entryIndex == forkIndex }
                    .archived,
            )

            repository.delete(sourceIndex)
            assertEquals(sourceIndex, repository.create())
            assertEquals(
                false,
                repository.listEntries(includeArchived = true)
                    .single { entry -> entry.entryIndex == sourceIndex }
                    .archived,
            )
        }

        test("returns one cached root instance and persists its recursive tree") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.createInitialized(settings())
            val root = repository.open(index)
            val first = root.spawnInitialized("first")
            val second = root.spawnInitialized("second")
            val nested = first.spawnInitialized("nested")

            assertSame(root, repository.open(index))
            assertEquals(
                listOf(first.storage.id, second.storage.id),
                root.subagents.list().map { entry -> root.subagents.open(entry).storage.id })
            assertEquals(
                listOf(nested.storage.id),
                first.subagents.list().map { entry -> first.subagents.open(entry).storage.id })
        }

        test("each Agent manages its direct entries") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.createInitialized(settings("root")))
            val first = root.subagents.create()
            val second = root.subagents.create()

            assertEquals(listOf(first, second), root.subagents.list())
            assertFalse(
                root.subagents.listEntries().any { entry ->
                    entry is KodexRootSessionEntry
                },
            )
            assertEquals(-1, root.subagents.open(first).storage.latestIndex())

            root.subagents.delete(first)

            assertEquals(listOf(second), root.subagents.list())
            assertFailsWith<IllegalArgumentException> { root.subagents.open(first) }
        }

        test("publishes ordered direct entry snapshots") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())

            assertEquals(emptyList(), repository.entries.value)
            val rootIndex = repository.create()
            assertEquals(listOf(rootIndex), repository.entries.value)
            val root = repository.open(rootIndex)
            assertEquals(emptyList(), root.subagents.entries.value)

            val first = root.subagents.create()
            val second = root.subagents.create()
            assertEquals(listOf(first, second), root.subagents.entries.value)

            root.subagents.delete(first)
            assertEquals(listOf(second), root.subagents.entries.value)
            assertEquals(first, root.subagents.create())
            assertEquals(listOf(first, second), root.subagents.entries.value)

            repository.delete(rootIndex)
            assertEquals(emptyList(), repository.entries.value)
        }

        test("delete invalidates cached nodes and releases the numeric slot") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.createInitialized(settings())
            val root = repository.open(index)
            val child = root.spawnInitialized("child")

            repository.delete(index)

            assertFailsWith<IllegalStateException> { root.storage.settings.latestIndex() }
            assertFailsWith<IllegalStateException> { child.storage.settings.latestIndex() }
            assertEquals(0, repository.createInitialized(settings()))
        }

        test("fork is a downstream operation and does not copy descendants") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("Source"))
            val source = repository.open(sourceIndex)
            source.runtime.injectHistory(listOf(userMessage()))
            source.spawnInitialized("child")

            val targetIndex = repository.createFork(source.storage, from = 0, until = 2)
            val target = repository.open(targetIndex)
            val latest = target.storage.latestIndex()
            target.runtime.updateSettings(
                target.storage.settings[latest].copy(threadName = "[fork] Source"),
            )

            assertEquals(listOf(1), target.storage.stable.indexes().toList())
            assertEquals(userMessage(), target.storage.stable[1])
            assertEquals("[fork] Source", target.storage.settings[2].threadName)
            assertEquals(emptyList(), target.subagents.list())
        }

        test("owns each runtime for the complete Agent session lifecycle") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.createInitialized(settings("root")))
            val child = root.spawnInitialized("child")

            assertSame(root.storage, root.runtime.storage)
            assertSame(child.storage, child.runtime.storage)

            root.cancelAndJoin()

            assertFalse(root.runtime.coroutineContext[Job]?.isActive ?: true)
            assertFalse(child.runtime.coroutineContext[Job]?.isActive ?: true)
        }

        test("exposes one Unified Exec client for each Agent runtime") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.createInitialized(settings("root")))
            val child = root.spawnInitialized("child")

            assertNotSame(root.runtime.unifiedExecToolClient, child.runtime.unifiedExecToolClient)
        }

        test("runtime rejects concurrent resume operations") {
            val entered = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _, _, _, _ ->
                    flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                root.runtime.resume()
            }
            entered.await()
            assertSame(first, root.runtime.runningTurn.value)

            assertFailsWith<ConcurrentAgentRuntimeResumeException> {
                root.runtime.resume()
            }
            assertSame(first, root.runtime.runningTurn.value)

            first.cancelJobAndJoin()
            assertNull(root.runtime.runningTurn.value)
        }

        test("runtime leaves host-owned pending calls in state") {
            val client = mockOpenAiClient {
                createResponse { _, _, _, _ ->
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = ResponseItem.FunctionCall(
                                name = "host_only",
                                arguments = "{}",
                                callId = "call_host_only",
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Ask the host.")))

            root.runtime.resume()

            val pending = assertIs<KodexAgentStateValue.ToolPending>(root.runtime.state.value)
            assertEquals(listOf("call_host_only"), pending.events.map { event -> event.callId })
        }

        test("cancelling a turn fails its persisted pending tool calls") {
            val pending = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _, _, _, _ ->
                    flow {
                        emit(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = "unhandled",
                                    arguments = "{}",
                                    callId = "call_1",
                                ),
                            ),
                        )
                        pending.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))

            val turn = async(start = CoroutineStart.UNDISPATCHED) {
                root.runtime.resume()
            }
            pending.await()
            turn.cancelJobAndJoin()

            val failure = assertIs<StableTextToolEvent>(root.storage.stable[3])
            assertEquals("user interrupt", failure.result)
            assertEquals(false, failure.success)
            assertEquals(emptyList(), root.storage.unstable[3])
            assertEquals(KodexAgentStateValue.ToolCompleted, root.runtime.state.value)
            assertNull(root.runtime.runningTurn.value)
        }

        test("a child tool resolves its caller through the shared path resolver") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
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
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
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
                    child.runtime.state.first { state -> state == KodexAgentStateValue.AssistantMessage }
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

            root.runtime.resume()

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
                                else -> assistantResponse("Root complete.", "root_complete")
                            }
                        }
                    }
                    response
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(
                repository.createInitialized(
                    settings("root").copy(
                        agentMode = AgentMode.Multi,
                        requestUserInputMode = RequestUserInputMode.NoQuestion,
                    ),
                ),
            )

            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Delegate this task.")))
            root.runtime.resume()

            val child = root.subagents.open(root.subagents.list().single())
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10.seconds) {
                    child.runtime.state.first { state -> state == KodexAgentStateValue.AssistantMessage }
                    while (
                        root.storage.stable.indexes().toList().none { index ->
                            root.storage.stable[index].containsWorkerCompletion()
                        } && root.runtime.pendingSteer.value.none { input ->
                            input is StableCleanEvent.AgentMessage &&
                                input.containsText("Worker complete.")
                        }
                    ) {
                        delay(10.milliseconds)
                    }
                }
            }
            assertEquals("/root/worker", child.storage.settings[child.storage.latestIndex()].threadName)
            assertEquals(AgentMode.Multi, child.storage.settings[child.storage.latestIndex()].agentMode)
            assertEquals(
                RequestUserInputMode.NoQuestion,
                child.storage.settings[child.storage.latestIndex()].requestUserInputMode,
            )
            val inheritedSettings = child.storage.settings[child.storage.latestIndex()]
            child.runtime.updateSettings(
                inheritedSettings.copy(
                    agentMode = AgentMode.Single,
                    requestUserInputMode = RequestUserInputMode.AskUser,
                ),
            )
            assertEquals(AgentMode.Single, child.storage.settings[child.storage.latestIndex()].agentMode)
            assertEquals(
                RequestUserInputMode.AskUser,
                child.storage.settings[child.storage.latestIndex()].requestUserInputMode,
            )
            assertEquals(AgentMode.Multi, root.storage.settings[root.storage.latestIndex()].agentMode)
            assertEquals(
                RequestUserInputMode.NoQuestion,
                root.storage.settings[root.storage.latestIndex()].requestUserInputMode,
            )
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
