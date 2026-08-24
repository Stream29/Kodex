package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.codexRequestWindowId
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.forkTo
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FailedResponse
import io.github.stream29.kodex.openai.IncompleteDetails
import io.github.stream29.kodex.openai.IncompleteResponse
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseStreamIncompleteException
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseError
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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

val kodexAgentStateImplTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("state owns a cancellable child scope") {
            val owner = supervisorChildScope()
            val agent = owner.KodexAgentState(
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
            val storage = InMemoryKodexAgentStorage.empty()
            val agent = KodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            val settings = settings()

            assertEquals(-1, agent.latestIndex.value)
            assertEquals(KodexAgentStateValue.Empty, agent.state.value)
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
            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
            assertEquals(userEvent("persisted"), storage.stable[1])
        }

        test("queued writes are fair and a cancelled waiter never enters its mutation") {
            val storage = storage()
            val agent = KodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            val holderStarted = CompletableDeferred<Unit>()
            val releaseHolder = CompletableDeferred<Unit>()
            val executionOrder = mutableListOf<String>()
            val holder = async(start = CoroutineStart.UNDISPATCHED) {
                agent.modify {
                    holderStarted.complete(Unit)
                    releaseHolder.await()
                }
            }
            holderStarted.await()

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                agent.modify { executionOrder += "first" }
            }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                agent.modify {
                    executionOrder += "cancelled"
                    it.stable[1] = userEvent("must not be written")
                }
            }
            val last = async(start = CoroutineStart.UNDISPATCHED) {
                agent.modify { executionOrder += "last" }
            }
            assertFalse(first.isCompleted)
            assertFalse(cancelled.isCompleted)
            assertFalse(last.isCompleted)

            cancelled.cancel()
            cancelled.join()
            releaseHolder.complete(Unit)
            holder.await()
            first.await()
            last.await()

            assertTrue(cancelled.isCancelled)
            assertEquals(listOf("first", "last"), executionOrder)
            assertEquals(-1, storage.stable.latestIndex())
            assertEquals(0, agent.latestIndex.value)
            assertEquals(KodexAgentStateValue.Empty, agent.state.value)
        }

        test("forked clean timelines reconstruct assistant state") {
            val source = storage().apply {
                stable[1] = userEvent("Question")
                stable[2] = assistantEvent("Answer")
            }
            val target = InMemoryKodexAgentStorage.empty()
            val agent = KodexAgentState(
                client = mockOpenAiClient(),
                storage = target,
            )

            agent.modify { mutable -> source.forkTo(until = 3, target = mutable) }

            assertEquals(KodexAgentStateValue.AssistantMessage, agent.state.value)
            assertEquals(listOf(1, 2), target.stable.indexes().toList())
            assertEquals(userEvent("Question"), target.stable[1])
            assertEquals(assistantEvent("Answer"), target.stable[2])
        }

        test("user append and clean injection write only stable history") {
            val storage = storage()
            val agent = KodexAgentState(
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
            assertEquals(KodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("agent-message injection restores requestable user state") {
            val storage = storage()
            val event = StableCleanEvent.AgentMessage(
                author = "/root",
                recipient = "/root/review",
                content = listOf(AgentMessageInputContent.InputText("Review this.")),
            )
            val agent = KodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )

            agent.injectHistory(listOf(event))

            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
            val reloaded = KodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            assertEquals(KodexAgentStateValue.UserMessage, reloaded.state.value)
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
            val agent = KodexAgentState(
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
            agent.requestResponseApi()

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
            assertEquals(KodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("response request maps retryable terminals and throws incomplete responses") {
            suspend fun requestFinishReason(
                events: List<ResponsesStreamEvent>,
            ): RequestFinish {
                val agent = KodexAgentState(
                    client = mockOpenAiClient {
                        createResponse { flowOf(*events.toTypedArray()) }
                    },
                    storage = storage(),
                )
                agent.appendUserMessage(userMessage("Finish.").content)
                return agent.requestResponseApi()
            }

            assertEquals(
                RequestFinish.Finish,
                requestFinishReason(
                    listOf(ResponsesStreamEvent.Completed(Response(id = "end", endTurn = true))),
                ),
            )
            assertEquals(
                RequestFinish.Continue,
                requestFinishReason(
                    listOf(ResponsesStreamEvent.Completed(Response(id = "continue", endTurn = false))),
                ),
            )

            val responseError = ResponseError(
                message = "Context limit exceeded.",
                code = "context_length_exceeded",
                type = "invalid_request_error",
            )
            assertEquals(
                RequestFinish.Retryable,
                requestFinishReason(
                    listOf(ResponsesStreamEvent.Failed(FailedResponse(responseError))),
                ),
            )

            val incompleteDetails = IncompleteDetails(reason = "max_output_tokens")
            val incomplete = assertFailsWith<OpenAiResponseStreamIncompleteException> {
                requestFinishReason(
                    listOf(
                        ResponsesStreamEvent.Incomplete(
                            IncompleteResponse(incompleteDetails),
                        ),
                    ),
                )
            }
            assertEquals(incompleteDetails, incomplete.incompleteDetails)
            assertEquals(
                "OpenAI response stream was incomplete (reason=max_output_tokens).",
                incomplete.message,
            )

            assertEquals(
                RequestFinish.Retryable,
                requestFinishReason(emptyList()),
            )
        }

        test("provider tool call enters unstable and completion moves it to stable") {
            val storage = storage()
            val call = functionCall("echo", "call_1", """{"value":"hello"}""")
            val agent = KodexAgentState(
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
            val finishReason = agent.requestResponseApi()

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
            assertEquals(
                RequestFinish.Continue,
                finishReason,
            )
            assertEquals(KodexAgentStateValue.ToolPending(listOf(pendingTool(call))), agent.state.value)

            val completed = completedTool(call, "hello")
            agent.completeToolCall(completed)

            assertEquals(completed, storage.stable[3])
            assertEquals(emptyList(), storage.unstable[3])
            assertEquals(KodexAgentStateValue.ToolCompleted, agent.state.value)
        }

        test("tool completion is rejected while an active response owns state") {
            val storage = storage()
            val call = functionCall("echo", "call_queued")
            val responseReachedPending = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flow {
                            emit(ResponsesStreamEvent.OutputItemDone(0, call))
                            responseReachedPending.complete(Unit)
                            releaseResponse.await()
                            emit(ResponsesStreamEvent.Completed(Response(id = "response_queued", endTurn = false)))
                        }
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Queue the tool output.").content)
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                agent.requestResponseApi()
            }
            responseReachedPending.await()

            val completed = completedTool(call, "queued result")
            val failure = assertFailsWith<KodexAgentStateInvalidTransitionException> {
                agent.completeToolCall(completed)
            }
            assertIs<KodexAgentStateValue.RequestResponse>(failure.currentState)
            assertIs<KodexAgentStateValue.RequestResponse>(agent.state.value)

            releaseResponse.complete(Unit)
            response.await()
            assertIs<KodexAgentStateValue.ToolPending>(agent.state.value)
            val completedAt = agent.completeToolCall(completed)

            assertEquals(completed, storage.stable[completedAt])
            assertEquals(emptyList(), storage.unstable[completedAt])
            assertEquals(KodexAgentStateValue.ToolCompleted, agent.state.value)
        }

        test("clear pending completes every tool call with user interrupt") {
            val storage = storage()
            val first = functionCall("first", "call_first")
            val second = functionCall("second", "call_second")
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, first),
                            ResponsesStreamEvent.OutputItemDone(1, second),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Run both.").content)
            agent.requestResponseApi()

            assertEquals(5, agent.clearPending())
            assertEquals(failedTool(first, "user interrupt"), storage.stable[4])
            assertEquals(listOf(pendingTool(second)), storage.unstable[4])
            assertEquals(failedTool(second, "user interrupt"), storage.stable[5])
            assertEquals(emptyList(), storage.unstable[5])
            assertEquals(KodexAgentStateValue.ToolCompleted, agent.state.value)
            assertEquals(5, agent.clearPending())
        }

        test("tool completions may arrive out of order") {
            val storage = storage()
            val first = functionCall("first", "call_first")
            val second = functionCall("second", "call_second")
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, first),
                            ResponsesStreamEvent.OutputItemDone(1, second),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Run both.").content)
            agent.requestResponseApi()

            val secondCompleted = completedTool(second, "second result")
            agent.completeToolCall(secondCompleted)
            assertEquals(
                listOf(first.callId),
                storage.unstable[4].filterIsInstance<PendingToolEvent>().map { it.callId },
            )
            assertEquals(KodexAgentStateValue.ToolPending(listOf(pendingTool(first))), agent.state.value)

            val firstCompleted = completedTool(first, "first result")
            agent.completeToolCall(firstCompleted)
            assertEquals(emptyList(), storage.unstable[5])
            assertEquals(secondCompleted, storage.stable[4])
            assertEquals(firstCompleted, storage.stable[5])
            assertEquals(KodexAgentStateValue.ToolCompleted, agent.state.value)
        }

        test("next request projects stable completion and omits obsolete unstable call") {
            val storage = storage()
            val requests = mutableListOf<ResponsesApiRequest>()
            val call = functionCall("echo", "call_1")
            var requestNumber = 0
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse { request ->
                        requests += request
                        requestNumber += 1
                        if (requestNumber == 1) {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, call),
                                ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                            )
                        } else {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, assistantMessage("Finished.")),
                                ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                            )
                        }
                    }
                },
                storage = storage,
            )

            val user = userMessage("Run.")
            agent.appendUserMessage(user.content)
            agent.requestResponseApi()
            val completed = completedTool(call, "result")
            agent.completeToolCall(completed)
            agent.requestResponseApi()

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
            val agent = KodexAgentState(
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
            agent.requestResponseApi()

            assertEquals(listOf(PendingServerToolSearch(call)), storage.unstable[2])
            assertEquals(StableCleanEvent.ServerToolSearch(call, output), storage.stable[3])
            assertEquals(emptyList(), storage.unstable[3])
            assertEquals(assistantEvent("No tool found."), storage.stable[4])
            assertEquals(KodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("unpaired server tool search remains in unstable history") {
            val storage = storage()
            val call = ResponseItem.ServerToolSearchCall(
                arguments = buildJsonObject { put("query", "tools") },
            )
            val agent = KodexAgentState(
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
                agent.requestResponseApi()
            }
            assertEquals(listOf(1), storage.stable.indexes().toList())
            assertEquals(listOf(PendingServerToolSearch(call)), storage.unstable[2])
        }

        test("forced compaction stores clean prefix and resets reported usage to zero") {
            val storage = storage()
            val initialCheckpoint = storage.compaction[0]
            val compaction = ResponseItem.Compaction(encryptedContent = "compact")
            val compactRequests = mutableListOf<ResponsesApiRequest>()
            val agent = KodexAgentState(
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
            assertEquals(0L, storage.tokenCount[compactIndex])
            assertEquals(compactIndex, storage.tokenCount.latestIndex())
            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
            assertEquals(
                listOf(user, ResponseItem.CompactionTrigger),
                compactRequests.single().input,
            )
        }

        test("forced compaction writes zero when completed response omits usage") {
            val storage = storage()
            storage.tokenCount[0] = 90L
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        RemoteCompactionV2Response(
                            compactionOutput = ResponseItem.Compaction(encryptedContent = "compact"),
                            completedResponse = Response(id = "compact_response"),
                        )
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Compact without usage.").content)

            val compactIndex = agent.forcedCompact()

            assertEquals(0L, storage.tokenCount[compactIndex])
            assertEquals(compactIndex, storage.tokenCount.latestIndex())
        }

        test("settings updates wait for remote compaction to publish its checkpoint") {
            val storage = storage()
            val compactionStarted = CompletableDeferred<Unit>()
            val releaseCompaction = CompletableDeferred<Unit>()
            val compactionItem = ResponseItem.Compaction(encryptedContent = "queued-compaction")
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        compactionStarted.complete(Unit)
                        releaseCompaction.await()
                        RemoteCompactionV2Response(compactionItem, null)
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Compact before updating settings.").content)
            val compaction = async(start = CoroutineStart.UNDISPATCHED) {
                agent.forcedCompact()
            }
            compactionStarted.await()

            val settingsUpdate = async(start = CoroutineStart.UNDISPATCHED) {
                agent.updateSettings(settings().copy(threadName = "After compaction"))
            }
            assertFalse(settingsUpdate.isCompleted)

            releaseCompaction.complete(Unit)
            val compactedAt = compaction.await()
            val settingsAt = settingsUpdate.await()

            assertTrue(settingsAt > compactedAt)
            assertEquals(compactionItem, storage.compaction[compactedAt].compaction)
            assertEquals("After compaction", storage.settings[settingsAt].threadName)
            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
        }

        test("post-compaction request projects checkpoint without duplicate marker") {
            val storage = storage()
            val requests = mutableListOf<ResponsesApiRequest>()
            val compaction = ResponseItem.Compaction(encryptedContent = "compact")
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createRemoteCompactionV2Response { _, _, _, _ ->
                        RemoteCompactionV2Response(compaction, null)
                    }
                    createResponse { request ->
                        requests += request
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage("After.")),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                        )
                    }
                },
                storage = storage,
            )

            val user = userMessage("Before.")
            agent.appendUserMessage(user.content)
            agent.forcedCompact()
            agent.requestResponseApi()

            assertEquals(listOf(user, compaction), requests.single().input.takeLast(2))
            assertEquals(assistantEvent("After."), storage.stable[3])
        }

        test("mark new turn rotates id without writing conversation events") {
            val storage = storage()
            val agent = KodexAgentState(
                client = mockOpenAiClient(),
                storage = storage,
            )
            val firstTurn = storage.settings[0].turnId

            assertEquals(0, agent.markNewTurn())
            agent.appendUserMessage(userMessage("First.").content)
            val markerIndex = agent.markNewTurn()

            assertNotEquals(firstTurn, storage.settings[markerIndex].turnId)
            assertEquals(listOf(1), storage.stable.indexes().toList())
            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
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
            val agent = KodexAgentState(
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
                            emit(ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)))
                        }
                    }
                },
                storage = storage,
            )

            agent.appendUserMessage(userMessage("Stream.").content)
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                agent.requestResponseApi()
            }
            val active = agent.state.first { value ->
                value is KodexAgentStateValue.RequestResponse.Message
            }
            val message = assertIs<KodexAgentStateValue.RequestResponse.Message>(active)
            assertEquals(listOf(added), message.events.replayCache)

            release.complete(Unit)
            running.await()
            assertEquals(KodexAgentStateValue.AssistantMessage, agent.state.value)
        }

        test("settings update commits during an active response and applies to the next request") {
            val storage = storage()
            val updatedModel = OpenAiModelId("updated-model")
            val requests = mutableListOf<ResponsesApiRequest>()
            val requestStarted = CompletableDeferred<Unit>()
            val releaseFirstRequest = CompletableDeferred<Unit>()
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse { request ->
                        requests += request
                        if (requests.size == 1) {
                            flow {
                                requestStarted.complete(Unit)
                                releaseFirstRequest.await()
                                emit(ResponsesStreamEvent.OutputItemDone(0, assistantMessage("First.")))
                                emit(ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)))
                            }
                        } else {
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(0, assistantMessage("Second.")),
                                ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                            )
                        }
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Use the initial model.").content)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                agent.requestResponseApi()
            }
            requestStarted.await()

            val settingsAt = agent.updateSettings(settings().copy(model = updatedModel))

            assertEquals(2, settingsAt)
            assertEquals(updatedModel, storage.settings[settingsAt].model)
            assertIs<KodexAgentStateValue.RequestResponse>(agent.state.value)
            assertFalse(first.isCompleted)

            releaseFirstRequest.complete(Unit)
            first.await()
            assertEquals(assistantEvent("First."), storage.stable[3])

            agent.requestResponseApi()

            assertEquals(
                listOf(OpenAiModelId("test-model"), updatedModel),
                requests.map(ResponsesApiRequest::model),
            )
        }

        test("cancelled active response restores stable state after a settings update") {
            val storage = storage()
            val requestStarted = CompletableDeferred<Unit>()
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flow {
                            requestStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Cancel this request.").content)
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                agent.requestResponseApi()
            }
            requestStarted.await()

            val settingsAt = agent.updateSettings(settings().copy(threadName = "Updated while active"))
            running.cancel()
            running.join()

            assertTrue(running.isCancelled)
            assertEquals(settingsAt, agent.latestIndex.value)
            assertEquals("Updated while active", storage.settings[settingsAt].threadName)
            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
        }

        test("concurrent request is rejected from the active response state") {
            val storage = storage()
            val call = functionCall("echo", "call_blocks_next_request")
            val firstRequestReachedPending = CompletableDeferred<Unit>()
            val releaseFirstRequest = CompletableDeferred<Unit>()
            var requestCount = 0
            val agent = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        requestCount += 1
                        flow {
                            emit(ResponsesStreamEvent.OutputItemDone(0, call))
                            firstRequestReachedPending.complete(Unit)
                            releaseFirstRequest.await()
                            emit(ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)))
                        }
                    }
                },
                storage = storage,
            )
            agent.appendUserMessage(userMessage("Run one request.").content)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                agent.requestResponseApi()
            }
            firstRequestReachedPending.await()

            val failure = assertFailsWith<KodexAgentStateInvalidTransitionException> {
                agent.requestResponseApi()
            }
            assertIs<KodexAgentStateValue.RequestResponse>(failure.currentState)
            assertIs<KodexAgentStateValue.RequestResponse>(agent.state.value)
            assertEquals(1, requestCount)

            releaseFirstRequest.complete(Unit)
            first.await()

            assertIs<KodexAgentStateValue.ToolPending>(agent.state.value)
        }
    }
}

private fun settings(): KodexAgentSettings =
    KodexAgentSettings(
        model = OpenAiModelId("test-model"),
        turnId = "turn-0",
    )

private fun storage(): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(settings())

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

private suspend fun KodexAgentStateContract.appendUserMessage(
    message: ResponseItem.Message,
): Int {
    require(message.role == MessageRole.User)
    return appendUserMessage(message.content)
}
