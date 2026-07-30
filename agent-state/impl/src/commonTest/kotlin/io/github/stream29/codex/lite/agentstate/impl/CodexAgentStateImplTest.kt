package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.contract.clearPending
import io.github.stream29.codex.lite.agentstate.contract.forcedCompact
import io.github.stream29.codex.lite.agentstorage.cleanmodels.codexRequestWindowId
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.ReasoningItemReasoningSummary
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.TokenUsage
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val codexAgentStateImplTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("state owns a cancellable child scope") {
            val owner = supervisorChildScope()
            val agent = owner.CodexAgentState(
                client = mockOpenAiClient(),
                storage = storage(),
            )

            assertNotEquals(owner.coroutineContext.job, agent.coroutineContext.job)
            agent.cancelAndJoin()

            assertTrue(owner.coroutineContext.job.isActive)
            assertFalse(agent.coroutineContext.job.isActive)
            owner.cancelAndJoin()
        }

        test("initialization and failed external writes refresh observable clean state") {
            val storage = InMemoryCodexAgentStorage.empty()
            val agent = CodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            val settings = settings()

            assertEquals(-1, agent.latestIndex.value)
            assertEquals(CodexAgentStateValue.Empty, agent.state.value)
            assertEquals(
                0,
                agent.modify { mutable ->
                    mutable.initialize(settings)
                    mutable.latestIndex()
                },
            )

            assertFailsWith<IllegalStateException> {
                agent.modify { mutable ->
                    mutable.stable[1] = userEvent("persisted")
                    error("stop after write")
                }
            }

            assertEquals(1, agent.latestIndex.value)
            assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
            assertEquals(userEvent("persisted"), storage.stable[1])
        }

        test("forked clean timelines reconstruct assistant state") {
            val source = storage().apply {
                stable[1] = userEvent("Question")
                stable[2] = assistantEvent("Answer")
            }
            val target = InMemoryCodexAgentStorage.empty()
            val agent = CodexAgentState(
                client = mockOpenAiClient(),
                storage = target,
            )

            agent.modify { mutable -> source.forkTo(until = 3, target = mutable) }

            assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
            assertEquals(listOf(1, 2), target.stable.indexes().toList())
            assertEquals(userEvent("Question"), target.stable[1])
            assertEquals(assistantEvent("Answer"), target.stable[2])
        }

        test("user append and clean injection write only stable history") {
            val storage = storage()
            val agent = CodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            val developer = StableCleanEvent.DeveloperMessage(
                listOf(ContentItem.InputText("Host context.")),
            )
            val reasoningItem = ResponseItem.Reasoning(
                summary = listOf(ReasoningItemReasoningSummary.SummaryText("Inspecting")),
            )
            val reasoning = StableCleanEvent.Reasoning(reasoningItem)
            val assistant = assistantEvent("Prior answer.")

            assertEquals(1, agent.appendUserMessage(userMessage("Implement.").content))
            assertEquals(4, agent.injectHistory(listOf(developer, reasoning, assistant)))

            assertEquals(
                listOf(userEvent("Implement."), developer, reasoning, assistant),
                storage.stable.indexes().toList().map { index -> storage.stable[index] },
            )
            assertEquals(-1, storage.unstable.latestIndex())
            assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("agent-message injection restores requestable user state") {
            val storage = storage()
            val event = StableCleanEvent.AgentMessage(
                author = "/root",
                recipient = "/root/review",
                content = listOf(AgentMessageInputContent.InputText("Review this.")),
            )
            val agent = CodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )

            agent.injectHistory(listOf(event))

            assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
            val reloaded = CodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            assertEquals(CodexAgentStateValue.UserMessage, reloaded.state.value)
        }

        test("response output persists reasoning and assistant as stable events") {
            val storage = storage()
            val requests = mutableListOf<ResponsesApiRequest>()
            val reasoningItem = ResponseItem.Reasoning(
                id = ResponseItemId("reasoning_1"),
                summary = listOf(ReasoningItemReasoningSummary.SummaryText("Thinking")),
            )
            val assistantItem = assistantMessage("Done.").copy(
                id = ResponseItemId("message_1"),
            )
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse { request ->
                        requests += request
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, reasoningItem),
                            ResponsesStreamEvent.OutputItemDone(1, assistantItem),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_1",
                                    usage = TokenUsage(10, 2, 12),
                                    endTurn = true,
                                ),
                            ),
                        )
                    }
                },
                storage = storage,
            )

            val user = userMessage("Answer.")
            agent.appendUserMessage(user.content)
            agent.requestResponseApi().toList()

            assertEquals(listOf(user), requests.single().input.takeLast(1))
            assertEquals(StableCleanEvent.Reasoning(reasoningItem), storage.stable[2])
            assertEquals(
                StableCleanEvent.AssistantMessage(
                    content = assistantItem.content,
                    id = assistantItem.id,
                    phase = assistantItem.phase,
                ),
                storage.stable[3],
            )
            assertEquals(12L, storage.tokenCount[4])
            assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("provider tool call enters unstable and completion moves it to stable") {
            val storage = storage()
            val call = functionCall("echo", "call_1", """{"value":"hello"}""")
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Use a tool.").content)
            agent.requestResponseApi().toList()

            assertEquals(
                listOf(
                    PendingFunctionToolEvent(
                        callId = call.callId,
                        itemId = call.id,
                        name = call.name,
                        arguments = OpenAiJsonCodec.parseToJsonElement(call.arguments),
                    ),
                ),
                storage.unstable[2],
            )
            assertEquals(CodexAgentStateValue.ToolPending(listOf(pendingTool(call))), agent.state.value)

            val completed = completedTool(call, "hello")
            agent.completeToolCall(completed)

            assertEquals(completed, storage.stable[3])
            assertEquals(emptyList(), storage.unstable[3])
            assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
        }

        test("clear pending completes every tool call with user interrupt") {
            val storage = storage()
            val first = functionCall("first", "call_first")
            val second = functionCall("second", "call_second")
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, first),
                            ResponsesStreamEvent.OutputItemDone(1, second),
                        )
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Run both.").content)
            agent.requestResponseApi().toList()

            assertEquals(5, agent.clearPending())
            assertEquals(failedTool(first, "user interrupt"), storage.stable[4])
            assertEquals(listOf(pendingTool(second)), storage.unstable[4])
            assertEquals(failedTool(second, "user interrupt"), storage.stable[5])
            assertEquals(emptyList(), storage.unstable[5])
            assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
            assertEquals(5, agent.clearPending())
        }

        test("tool completions may arrive out of order") {
            val storage = storage()
            val first = functionCall("first", "call_first")
            val second = functionCall("second", "call_second")
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, first),
                            ResponsesStreamEvent.OutputItemDone(1, second),
                        )
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Run both.").content)
            agent.requestResponseApi().toList()

            val secondCompleted = completedTool(second, "second result")
            agent.completeToolCall(secondCompleted)
            assertEquals(
                listOf(first.callId),
                storage.unstable[4].filterIsInstance<PendingToolEvent>().map { it.callId },
            )
            assertEquals(CodexAgentStateValue.ToolPending(listOf(pendingTool(first))), agent.state.value)

            val firstCompleted = completedTool(first, "first result")
            agent.completeToolCall(firstCompleted)
            assertEquals(emptyList(), storage.unstable[5])
            assertEquals(secondCompleted, storage.stable[4])
            assertEquals(firstCompleted, storage.stable[5])
            assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
        }

        test("next request projects stable completion and omits obsolete unstable call") {
            val storage = storage()
            val requests = mutableListOf<ResponsesApiRequest>()
            val call = functionCall("echo", "call_1")
            var requestNumber = 0
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse { request ->
                        requests += request
                        requestNumber += 1
                        if (requestNumber == 1) {
                            flowOf(ResponsesStreamEvent.OutputItemDone(0, call))
                        } else {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, assistantMessage("Finished.")),
                            )
                        }
                    }
                },
                storage = storage,
            )

            val user = userMessage("Run.")
            agent.appendUserMessage(user.content)
            agent.requestResponseApi().toList()
            val completed = completedTool(call, "result")
            agent.completeToolCall(completed)
            agent.requestResponseApi().toList()

            assertEquals(
                listOf(user) + completed.toResponseHistoryItems(),
                requests[1].input.takeLast(3),
            )
        }

        test("hosted server tool search is stored as one paired stable event") {
            val storage = storage()
            val call = ResponseItem.ServerToolSearchCall(
                id = ResponseItemId("search_call"),
                status = "completed",
                arguments = buildJsonObject { put("query", "tools") },
            )
            val output = ResponseItem.ServerToolSearchOutput(
                id = ResponseItemId("search_output"),
                status = "completed",
                tools = emptyList(),
            )
            val assistant = assistantMessage("No tool found.")
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.OutputItemDone(1, output),
                            ResponsesStreamEvent.OutputItemDone(2, assistant),
                            ResponsesStreamEvent.Completed(Response(id = "response_1")),
                        )
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Search tools.").content)
            agent.requestResponseApi().toList()

            assertEquals(listOf(PendingServerToolSearch(call)), storage.unstable[2])
            assertEquals(StableCleanEvent.ServerToolSearch(call, output), storage.stable[3])
            assertEquals(emptyList(), storage.unstable[3])
            assertEquals(assistantEvent("No tool found."), storage.stable[4])
            assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("unpaired server tool search remains in unstable history") {
            val storage = storage()
            val call = ResponseItem.ServerToolSearchCall(
                arguments = buildJsonObject { put("query", "tools") },
            )
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.Completed(Response(id = "response_1")),
                        )
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Search tools.").content)

            assertFailsWith<IllegalStateException> {
                agent.requestResponseApi().toList()
            }
            assertEquals(listOf(1), storage.stable.indexes().toList())
            assertEquals(listOf(PendingServerToolSearch(call)), storage.unstable[2])
        }

        test("forced compaction stores clean prefix and provider compaction once") {
            val storage = storage()
            val initialCheckpoint = storage.compaction[0]
            val compaction = ResponseItem.Compaction(encryptedContent = "compact")
            val compactRequests = mutableListOf<ResponsesApiRequest>()
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createRemoteCompactionV2Response { request, _, _, windowId ->
                        compactRequests += request
                        assertEquals(
                            initialCheckpoint.codexRequestWindowId(storage.id.toCodexThreadId()),
                            windowId,
                        )
                        RemoteCompactionV2Response(
                            compactionOutput = compaction,
                            completedResponse = Response(
                                id = "compact_response",
                                usage = TokenUsage(10, 1, 11),
                            ),
                        )
                    }
                },
                storage = storage,
            )

            val user = userMessage("Compact this context.")
            agent.appendUserMessage(user.content)
            val compactIndex = agent.forcedCompact()

            val checkpoint = storage.compaction[compactIndex]
            assertEquals(listOf(userEvent("Compact this context.")), checkpoint.prefix)
            assertEquals(compaction, checkpoint.compaction)
            assertEquals(listOf(user, compaction), checkpoint.toResponseHistoryItems())
            assertEquals(StableCleanEvent.ContextCompaction, storage.stable[compactIndex])
            assertEquals(compactIndex + 1, checkpoint.historyBaseIndex)
            assertEquals(11L, storage.tokenCount[compactIndex])
            assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
            assertEquals(
                listOf(user, ResponseItem.CompactionTrigger),
                compactRequests.single().input,
            )
        }

        test("post-compaction request projects checkpoint without duplicate marker") {
            val storage = storage()
            val requests = mutableListOf<ResponsesApiRequest>()
            val compaction = ResponseItem.Compaction(encryptedContent = "compact")
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        RemoteCompactionV2Response(compaction, null)
                    }
                    createResponse { request ->
                        requests += request
                        flowOf(ResponsesStreamEvent.OutputItemDone(0, assistantMessage("After.")))
                    }
                },
                storage = storage,
            )

            val user = userMessage("Before.")
            agent.appendUserMessage(user.content)
            agent.forcedCompact()
            agent.requestResponseApi().toList()

            assertEquals(listOf(user, compaction), requests.single().input.takeLast(2))
            assertEquals(assistantEvent("After."), storage.stable[3])
        }

        test("mark new turn rotates id without writing conversation events") {
            val storage = storage()
            val agent = CodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            val firstTurn = storage.settings[0].turnId

            assertEquals(0, agent.markNewTurn())
            agent.appendUserMessage(userMessage("First.").content)
            val markerIndex = agent.markNewTurn()

            assertNotEquals(firstTurn, storage.settings[markerIndex].turnId)
            assertEquals(listOf(1), storage.stable.indexes().toList())
            assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        }

        test("active request publishes streaming item state before completion") {
            val storage = storage()
            val addedItem = ResponseItem.Message(
                id = ResponseItemId("message_1"),
                role = MessageRole.Assistant,
                content = emptyList(),
            )
            val added = ResponsesStreamEvent.OutputItemAdded(0, addedItem)
            val release = CompletableDeferred<Unit>()
            val agent = CodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flow {
                            emit(added)
                            release.await()
                            emit(
                                ResponsesStreamEvent.OutputItemDone(
                                    0,
                                    addedItem.copy(content = listOf(ContentItem.OutputText("hello"))),
                                ),
                            )
                        }
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Stream.").content)
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                agent.requestResponseApi().toList()
            }
            val active = agent.state.first { value ->
                value is CodexAgentStateValue.RequestResponse.Message
            }
            val message = assertIs<CodexAgentStateValue.RequestResponse.Message>(active)
            assertEquals(listOf(added), message.events.replayCache)

            release.complete(Unit)
            running.await()
            assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        }
    }
}

private fun settings(): CodexAgentSettings =
    CodexAgentSettings(
        model = OpenAiModelId("test-model"),
        turnId = "turn-0",
    )

private fun storage(): InMemoryCodexAgentStorage =
    InMemoryCodexAgentStorage(settings())

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private fun userEvent(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(userMessage(text).content)

private fun assistantEvent(text: String): StableCleanEvent.AssistantMessage =
    StableCleanEvent.AssistantMessage(assistantMessage(text).content)

private fun functionCall(
    name: String,
    callId: String,
    arguments: String = "{}",
): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(
        id = ResponseItemId("item_$callId"),
        name = name,
        arguments = arguments,
        callId = callId,
    )

private fun completedTool(
    call: ResponseItem.FunctionCall,
    result: String,
): StableTextToolEvent =
    StableTextToolEvent(
        callId = call.callId,
        itemId = call.id,
        name = call.name,
        namespace = call.namespace,
        arguments = OpenAiJsonCodec.parseToJsonElement(call.arguments),
        result = result,
        success = true,
    )

private fun failedTool(
    call: ResponseItem.FunctionCall,
    result: String,
): StableTextToolEvent =
    completedTool(call, result).copy(success = false)

private fun pendingTool(call: ResponseItem.FunctionCall): PendingFunctionToolEvent =
    PendingFunctionToolEvent(
        callId = call.callId,
        itemId = call.id,
        name = call.name,
        namespace = call.namespace,
        arguments = OpenAiJsonCodec.parseToJsonElement(call.arguments),
    )

private suspend fun CodexAgentStateContract.appendUserMessage(
    message: ResponseItem.Message,
): Int {
    require(message.role == MessageRole.User)
    return appendUserMessage(message.content)
}
