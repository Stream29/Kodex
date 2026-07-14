package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstate.contract.forcedCompact
import io.github.stream29.codex.lite.agentstorage.contract.nextIndex
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.FailedResponse
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseError
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.TokenUsage
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class CodexAgentStateImplTest {
    @Test
    fun stateTracksPendingToolCallsAndRejectsMismatchedResults() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val user = userMessage("Run a command.")
        val call = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{}",
            callId = "call_1",
        )
        storage.history[1] = user
        storage.history[2] = call
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolPending(listOf(call)), agent.state.value)

        assertFailsWith<IllegalArgumentException> {
            agent.completeToolCall(
                ResponseItem.FunctionCallOutput(
                    callId = "other_call",
                    output = FunctionCallOutputPayload.fromText("no"),
                ),
            )
        }
        assertFailsWith<CodexAgentStateInvalidTransitionException> {
            agent.requestResponseApi().toList()
        }

        agent.completeToolCall(
            ResponseItem.FunctionCallOutput(
                callId = "call_1",
                output = FunctionCallOutputPayload.fromText("done"),
            ),
        )
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    @Test
    fun completeToolCallPersistsResultsOneAtATime() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val firstCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{}",
            callId = "call_1",
        )
        val secondCall = ResponseItem.CustomToolCall(
            name = "apply_patch",
            input = "*** Begin Patch",
            callId = "call_2",
        )
        storage.history[1] = userMessage("Run both tools.")
        storage.history[2] = firstCall
        storage.history[3] = secondCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolPending(listOf(firstCall, secondCall)), agent.state.value)

        val secondOutput = ResponseItem.CustomToolCallOutput(
            callId = secondCall.callId,
            name = secondCall.name,
            output = FunctionCallOutputPayload.fromText("second"),
        )
        val firstOutput = ResponseItem.FunctionCallOutput(
            callId = firstCall.callId,
            output = FunctionCallOutputPayload.fromText("first"),
        )

        val secondIndex = agent.completeToolCall(secondOutput)

        assertEquals(4, secondIndex)
        assertEquals(secondOutput, storage.history[4])
        assertEquals(CodexAgentStateValue.ToolPending(listOf(firstCall)), agent.state.value)

        val finalIndex = agent.completeToolCall(firstOutput)

        assertEquals(5, finalIndex)
        assertEquals(secondOutput, storage.history[4])
        assertEquals(firstOutput, storage.history[5])
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    @Test
    fun stateReconstructionPairsTailToolCallsBeforeChoosingState() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val firstCall = ResponseItem.FunctionCall(
            name = "first",
            arguments = "{}",
            callId = "call_1",
        )
        val secondCall = ResponseItem.CustomToolCall(
            name = "second",
            input = "input",
            callId = "call_2",
        )
        storage.history[1] = userMessage("Run both tools.")
        storage.history[2] = firstCall
        storage.history[3] = secondCall
        storage.history[4] = ResponseItem.CustomToolCallOutput(
            callId = secondCall.callId,
            output = FunctionCallOutputPayload.fromText("second"),
        )
        storage.history[5] = ResponseItem.FunctionCallOutput(
            callId = firstCall.callId,
            output = FunctionCallOutputPayload.fromText("first"),
        )

        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    @Test
    fun completeToolCallDoesNotUpdatePlan() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val planCall = ResponseItem.FunctionCall(
            name = "update_plan",
            arguments = "{}",
            callId = "plan_call",
        )
        storage.history[1] = userMessage("Start.")
        storage.history[2] = planCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        val outputIndex = agent.completeToolCall(
            ResponseItem.FunctionCallOutput(
                callId = planCall.callId,
                output = FunctionCallOutputPayload.fromText("Plan updated").copy(success = true),
            ),
        )

        assertEquals(3, outputIndex)
        assertEquals(null, storage.plan.nextIndex(3))
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    @Test
    fun defaultSettingsAllocateUuidV7TurnId() {
        val settings = CodexAgentSettings(OpenAiModelId("test-model"))

        assertEquals('7', settings.turnId[14])
        assertEquals(null, settings.autoCompactionTokenLimit)
        assertEquals(null, settings.installationId)
        assertEquals(null, settings.sessionId)
    }

    @Test
    fun initializedStorageLoadsAsAnEmptyAgentState() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(0, agent.latestIndex.value)
        assertEquals(CodexAgentStateValue.Empty, agent.state.value)
    }

    @Test
    fun responseFailureIsPublishedWithoutAgentStateException() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val failure = ResponsesStreamEvent.Failed(
            FailedResponse(ResponseError(message = "bad request")),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { flowOf<ResponsesStreamEvent>(failure) }
            },
            storage = storage,
        )
        val received = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start."))

        agent.requestResponseApi().collect(received::add)

        assertEquals(listOf<ResponsesStreamEvent>(failure), received)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, storage.latestIndex())
    }

    @Test
    fun resumeExecutesOneRequestWhenEndTurnIsFalse() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    when (requests.size) {
                        1 -> flow {
                            emit(
                                ResponsesStreamEvent.OutputItemDone(
                                    outputIndex = 0,
                                    item = ResponseItem.Reasoning(summary = emptyList()),
                                ),
                            )
                            emit(
                                ResponsesStreamEvent.OutputItemDone(
                                    outputIndex = 1,
                                    item = assistantMessage("Preparing the answer."),
                                ),
                            )
                            emit(
                                ResponsesStreamEvent.Completed(
                                    Response(
                                        id = "response_1",
                                        usage = TokenUsage(
                                            inputTokens = 10,
                                            outputTokens = 2,
                                            totalTokens = 12,
                                        ),
                                        endTurn = false,
                                    ),
                                ),
                            )
                        }

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = assistantMessage("Done."),
                            ),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_2",
                                    usage = TokenUsage(
                                        inputTokens = 12,
                                        outputTokens = 1,
                                        totalTokens = 13,
                                    ),
                                    endTurn = true,
                                ),
                            ),
                        )

                        else -> error("Unexpected request count ${requests.size}.")
                    }
                }
            },
            storage = storage,
        )

        val user = userMessage("Answer briefly.")
        assertEquals(1, agent.appendUserMessage(user, tokenCount = 1))

        agent.requestResponseApi().toList()

        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertEquals(1, requests.size)
        assertEquals(listOf(user), requests[0].input)
        assertEquals(4, storage.latestIndex())
        assertIs<ResponseItem.Reasoning>(storage.history[2])
        assertEquals(assistantMessage("Preparing the answer."), storage.history[3])
        assertEquals(null, storage.history.nextIndex(4))
        assertEquals(12, storage.tokenCount[4])
        assertTrue(storage.timestamp[4] > instant(0))
        assertEquals(4, agent.latestIndex.value)
    }

    @Test
    fun resumeSendsStoredReasoningItemsBackToModel() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val user = userMessage("Continue.")
        val reasoning = ResponseItem.Reasoning(summary = emptyList())
        val assistant = assistantMessage("Ready.")
        storage.history[1] = user
        storage.history[2] = reasoning
        storage.history[3] = assistant
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.Completed(
                            Response(id = "response_1", endTurn = true),
                        ),
                    )
                }
            },
            storage = storage,
        )

        agent.requestResponseApi().toList()

        assertEquals(
            listOf(user, reasoning, assistant),
            requests.single().input,
        )
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resumePublishesOutputItemAndRawStreamEventsBeforeCompleted() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val outputItem = assistantMessage("Streaming item is visible before completion.")
        val outputEvent = ResponsesStreamEvent.OutputItemDone(
            outputIndex = 0,
            item = outputItem,
        )
        val completedEvent = ResponsesStreamEvent.Completed(
            Response(
                id = "response_1",
                usage = TokenUsage(
                    inputTokens = 8,
                    outputTokens = 1,
                    totalTokens = 9,
                ),
                endTurn = true,
            ),
        )
        val outputItemCollected = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(outputEvent)
                        outputItemCollected.complete(Unit)
                        releaseCompletion.await()
                        emit(completedEvent)
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Start streaming."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().toList()
        }

        outputItemCollected.await()
        assertEquals(2, storage.latestIndex())
        assertEquals(outputItem, storage.history[2])
        assertEquals(2, agent.latestIndex.value)
        assertEquals(-1, storage.tokenCount.latestIndex())

        releaseCompletion.complete(Unit)
        assertEquals(listOf(outputEvent, completedEvent), runningResume.await())
        assertEquals(3, storage.latestIndex())
        assertEquals(null, storage.history.nextIndex(3))
        assertEquals(9, storage.tokenCount[3])
    }

    @Test
    fun resumeDoesNotWaitForSlowStreamEventCollector() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val deltaEvent = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "x",
        )
        val completedEvent = ResponsesStreamEvent.Completed(
            Response(
                id = "response_1",
                usage = TokenUsage(
                    inputTokens = 8,
                    outputTokens = 1,
                    totalTokens = 9,
                ),
                endTurn = true,
            ),
        )
        val productionCompleted = CompletableDeferred<Unit>()
        val firstEventCollected = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        repeat(1_024) {
                            emit(deltaEvent)
                        }
                        emit(completedEvent)
                        productionCompleted.complete(Unit)
                    }
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start streaming."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                if (collected.size == 1) {
                    firstEventCollected.complete(Unit)
                    releaseCollector.await()
                }
            }
        }

        firstEventCollected.await()
        yield()
        assertTrue(productionCompleted.isCompleted)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(9, storage.tokenCount[2])

        releaseCollector.complete(Unit)
        runningResume.await()
        assertEquals(1_025, collected.size)
        assertEquals(completedEvent, collected.last())
    }

    @Test
    fun cancellingResumeAfterDeltaResetsStateAndDoesNotPersistPartialText() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val deltaEvent = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "partial",
        )
        val deltaCollected = CompletableDeferred<Unit>()
        val releaseStream = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(deltaEvent)
                        deltaCollected.complete(Unit)
                        releaseStream.await()
                    }
                }
            },
            storage = storage,
        )
        val user = userMessage("Start streaming.")
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(user)
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().toList(collected)
        }

        deltaCollected.await()
        assertEquals(listOf<ResponsesStreamEvent>(deltaEvent), collected)

        runningResume.cancel(CancellationException("runtime interrupted stream"))
        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, storage.latestIndex())
        assertEquals(user, storage.history[1])
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(-1, storage.tokenCount.latestIndex())
    }

    @Test
    fun resumePropagatesCancellationExceptionWithoutWrappingIt() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        throw CancellationException("transport cancelled")
                    }
                }
            },
            storage = storage,
        )
        val user = userMessage("Start streaming.")

        agent.appendUserMessage(user)
        val exception = assertFailsWith<CancellationException> {
            agent.requestResponseApi().toList()
        }

        assertEquals("transport cancelled", exception.message)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, storage.latestIndex())
        assertEquals(user, storage.history[1])
    }

    @Test
    fun cancellingResumeFromOutputItemCollectorKeepsStableHistoryItem() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val outputItem = assistantMessage("Stable output.")
        val outputEvent = ResponsesStreamEvent.OutputItemDone(
            outputIndex = 0,
            item = outputItem,
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(outputEvent)
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                currentCoroutineContext().cancel(CancellationException("runtime cancelled after stable item"))
            }
        }

        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(listOf<ResponsesStreamEvent>(outputEvent), collected)
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(outputItem, storage.history[2])
        assertEquals(null, storage.history.nextIndex(3))
    }

    @Test
    fun cancellingResumeFromCompletedCollectorKeepsStableTokenCount() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val completedEvent = ResponsesStreamEvent.Completed(
            Response(
                id = "response_1",
                usage = TokenUsage(
                    inputTokens = 8,
                    outputTokens = 1,
                    totalTokens = 9,
                ),
                endTurn = true,
            ),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(completedEvent)
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                currentCoroutineContext().cancel(CancellationException("runtime cancelled after completion"))
            }
        }

        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(listOf<ResponsesStreamEvent>(completedEvent), collected)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(9, storage.tokenCount[2])
    }

    @Test
    fun resumePersistsToolCallOutputItem() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val toolCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = """{"cmd":"date"}""",
            callId = "call_1",
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = toolCall,
                        ),
                        ResponsesStreamEvent.Completed(
                            Response(id = "response_1", endTurn = true),
                        ),
                    )
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("What time is it?"))
        agent.requestResponseApi().toList()

        assertEquals(1, requests.size)
        assertEquals(2, storage.latestIndex())
        assertEquals(toolCall, storage.history[2])
        assertEquals(-1, storage.tokenCount.latestIndex())
    }

    @Test
    fun updateSettingsPublishesStateIndexWithoutHistoryItem() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("old-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = assistantMessage("Using the updated model."),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )
        val user = userMessage("Use the new settings.")

        agent.appendUserMessage(user, tokenCount = 1)
        val settingsIndex = agent.updateSettings(CodexAgentSettings(OpenAiModelId("new-model")))

        assertEquals(2, settingsIndex)
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(OpenAiModelId("new-model"), storage.settings[2].model)
        assertEquals(1, storage.tokenCount[2])

        agent.requestResponseApi().toList()

        assertEquals(OpenAiModelId("new-model"), requests.single().model)
        assertEquals(listOf(user), requests.single().input)
    }

    @Test
    fun appendPlanUpdatePublishesPlanAndMatchingToolResultAtomically() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val plan = UpdatePlanArgs(
            plan = listOf(PlanItemArg(step = "inspect", status = StepStatus.InProgress)),
        )
        val planCall = ResponseItem.FunctionCall(
            name = "update_plan",
            arguments = "{}",
            callId = "plan_call",
        )
        storage.history[1] = userMessage("Start.")
        storage.tokenCount[1] = 1
        storage.history[2] = planCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        val output = ResponseItem.FunctionCallOutput(
            callId = planCall.callId,
            output = FunctionCallOutputPayload.fromText("Plan updated").copy(success = true),
        )
        val planIndex = agent.appendPlanUpdate(
            output = output,
            plan = plan,
        )

        assertEquals(3, planIndex)
        assertEquals(plan, storage.plan[3])
        assertEquals(planCall, storage.history[2])
        assertEquals(output, storage.history[3])
        assertEquals(1, storage.tokenCount[3])
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    @Test
    fun mutationFailsWhenAnotherMutationIsRunning() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        requestStarted.complete(Unit)
                        releaseResponse.await()
                        emit(ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)))
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Wait."))
        val runningResume = async { agent.requestResponseApi().toList() }
        requestStarted.await()

        val exception = assertFailsWith<CodexAgentStateInvalidTransitionException> {
            agent.appendUserMessage(userMessage("Concurrent input."))
        }
        assertEquals(CodexAgentStateValue.RequestResponse, exception.currentState)

        releaseResponse.complete(Unit)
        runningResume.await()
    }

    @Test
    fun resumePersistsCompactionOutputWithoutRequestingCompaction() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val compactItem = ResponseItem.ContextCompaction(encryptedContent = "compact")
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = compactItem,
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )

        val user = userMessage("Compact.")
        agent.appendUserMessage(user)
        agent.requestResponseApi().toList()

        assertEquals(2, storage.latestIndex())
        assertEquals(compactItem, storage.history[2])
        assertEquals(0, storage.compaction[2].historyBaseIndex)
    }

    @Test
    fun forcedCompactUsesRemoteCompactionV2ByDefault() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val initialTurnId = storage.settings[0].turnId
        val initialCheckpoint = storage.compaction[0]
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(
                            inputTokens = 10,
                            outputTokens = 1,
                            totalTokens = 11,
                        ),
                    )
                }
            },
            storage = storage,
        )

        val user = userMessage("This context is too large.")
        agent.appendUserMessage(user)
        val compactIndex = agent.forcedCompact()

        assertEquals(2, compactIndex)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertEquals(listOf(user), compactRequest.history)
        assertEquals(RemoteCompactionV2Trigger.Manual, compactRequest.trigger)
        assertEquals(RemoteCompactionV2Reason.UserRequested, compactRequest.reason)
        assertEquals(RemoteCompactionV2Phase.StandaloneTurn, compactRequest.phase)
        assertEquals(null, compactRequest.settings.installationId)
        assertEquals(null, compactRequest.settings.sessionId)
        assertEquals(storage.id, compactRequest.threadId)
        assertTrue(compactRequest.settings.turnId != initialTurnId)
        assertEquals(initialCheckpoint, compactRequest.checkpoint)
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "compact"), storage.history[2])
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(user, compaction),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
        assertEquals(11, storage.tokenCount[2])
        assertEquals(compactRequest.settings.turnId, storage.settings[2].turnId)
        assertEquals(2, agent.latestIndex.value)
    }

    @Test
    fun remoteCompactionV2RetainsOnlyNewestUserMessagesWithinRustBudget() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val old = userMessage("old user message")
        val oversized = userMessage("x".repeat((64_000 + 2) * 4))
        val newest = userMessage("newest user message")
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        storage.history[1] = old
        storage.history[2] = oversized
        storage.history[3] = newest
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response {
                    remoteCompactionV2Response(compaction)
                }
            },
            storage = storage,
        )

        agent.forcedCompact()

        val prefix = storage.compaction[4].prefix
        assertEquals(3, prefix.size)
        val truncated = assertIs<ResponseItem.Message>(prefix[0])
        val retainedText = assertIs<ContentItem.InputText>(truncated.content.single()).text
        assertTrue(retainedText.contains("tokens truncated"))
        assertEquals(newest, prefix[1])
        assertEquals(compaction, prefix[2])
        assertTrue(prefix.none { it == old })
    }

    @Test
    fun remoteCompactionV2UsesStorageThreadIdentity() = runTest {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                installationId = "install",
                sessionId = "session",
            ),
        )
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Compact."))
        agent.forcedCompact()

        val compactRequest = compactRequests.single()
        assertEquals("install", compactRequest.settings.installationId)
        assertEquals("session", compactRequest.settings.sessionId)
        assertEquals(storage.id, compactRequest.threadId)
    }

    @Test
    fun remoteCompactionV2UsesWindowNumberFromCheckpoint() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val initialCheckpoint = CompactionCheckpoint(
            prefix = emptyList(),
            historyBaseIndex = 0,
            windowNumber = 7,
            firstWindowId = "window-7",
            windowId = "window-7",
        )
        val user = userMessage("Compact.")
        storage.compaction[1] = initialCheckpoint
        storage.history[1] = user
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.forcedCompact()

        val compactRequest = compactRequests.single()
        assertEquals(7L, compactRequest.checkpoint.windowNumber)
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(user, ResponseItem.Compaction(encryptedContent = "compact")),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
    }

    @Test
    fun resumeDoesNotApplyAutoCompactionPolicy() = runTest {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 90,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val user = userMessage("Keep this user message.")
        val compaction = ResponseItem.Compaction(encryptedContent = "pre-turn-compact")
        val final = assistantMessage("After pre-turn compact.")
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val responseRequests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(90, 1, 91),
                    )
                }

                createResponse { request, installationId, turnMetadata, windowId ->
                    responseRequests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, final),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(user, tokenCount = 90)
        agent.requestResponseApi().toList()

        assertEquals(0, compactRequests.size)
        assertEquals(1, responseRequests.size)
        assertEquals(listOf(user), responseRequests.single().request.input)
        assertEquals("${storage.id}:0", responseRequests.single().windowId)
        assertTrue(responseRequests.single().turnMetadata.contains("\"request_kind\":\"turn\""))
        assertEquals(final, storage.history[2])
        assertEquals(initialCheckpoint, storage.compaction[2])
    }

    @Test
    fun resumeDoesNotContinueOrCompactWhenFollowUpIsNeeded() = runTest {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 20,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val user = userMessage("Continue until final.")
        val partial = assistantMessage("Partial answer.")
        val compaction = ResponseItem.Compaction(encryptedContent = "mid-turn-compact")
        val final = assistantMessage("Final answer.")
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val responseRequests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(20, 1, 21),
                    )
                }

                createResponse { request, installationId, turnMetadata, windowId ->
                    responseRequests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
                    when (responseRequests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, partial),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_1",
                                    usage = TokenUsage(15, 5, 20),
                                    endTurn = false,
                                ),
                            ),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, final),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected response request count ${responseRequests.size}.")
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(user, tokenCount = 1)
        agent.requestResponseApi().toList()

        assertEquals(1, responseRequests.size)
        assertEquals(listOf(user), responseRequests[0].request.input)
        assertEquals("${storage.id}:0", responseRequests[0].windowId)
        assertTrue(responseRequests[0].turnMetadata.contains("\"request_kind\":\"turn\""))
        assertEquals(0, compactRequests.size)
        assertEquals(partial, storage.history[2])
        assertEquals(20, storage.tokenCount[3])
        assertEquals(initialCheckpoint, storage.compaction[3])
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
    }

    @Test
    fun resumeNeverAppliesAutoCompactionPolicyAcrossRequests() = runTest {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 20,
            ),
        )
        val user = userMessage("Answer once.")
        val firstFinal = assistantMessage("First final.")
        val compaction = ResponseItem.Compaction(encryptedContent = "next-turn-compact")
        val secondFinal = assistantMessage("Second final.")
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val responseRequests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(20, 1, 21),
                    )
                }

                createResponse { request, installationId, turnMetadata, windowId ->
                    responseRequests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
                    when (responseRequests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, firstFinal),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_1",
                                    usage = TokenUsage(16, 4, 20),
                                    endTurn = true,
                                ),
                            ),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, secondFinal),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected response request count ${responseRequests.size}.")
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(user, tokenCount = 1)
        agent.requestResponseApi().toList()

        assertEquals(1, responseRequests.size)
        assertEquals("${storage.id}:0", responseRequests.single().windowId)
        assertTrue(responseRequests.single().turnMetadata.contains("\"request_kind\":\"turn\""))
        assertEquals(0, compactRequests.size)
        assertEquals(firstFinal, storage.history[2])
        assertEquals(20, storage.tokenCount[3])

        agent.requestResponseApi().toList()

        assertEquals(2, responseRequests.size)
        assertEquals(0, compactRequests.size)
        assertEquals(listOf(user, firstFinal), responseRequests[1].request.input)
        assertEquals(secondFinal, storage.history[4])
    }

    @Test
    fun remoteCompactionV2ClientFailureDoesNotMutateStorage() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response {
                    throw IllegalStateException("bad remote compaction v2")
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Compact."))

        assertFailsWith<IllegalStateException> {
            agent.forcedCompact()
        }
        assertEquals(1, storage.latestIndex())
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
    }

}

private data class RecordedCreateResponse(
    val request: ResponsesApiRequest,
    val installationId: String?,
    val turnMetadata: String,
    val windowId: String,
)

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

private suspend fun CodexAgentStateContract.appendUserMessage(
    message: ResponseItem.Message,
    tokenCount: Long? = null,
): Int {
    require(message.role == MessageRole.User)
    return appendUserMessage(message.content).also { index ->
        if (tokenCount != null) {
            (storage as MutableCodexAgentStorage).tokenCount[index] = tokenCount
        }
    }
}

private fun instant(epochSecond: Long): Instant =
    Instant.fromEpochSeconds(epochSecond)

private fun remoteCompactionV2Response(
    compactionOutput: ResponseItem.Compaction,
    usage: TokenUsage? = null,
): RemoteCompactionV2Response =
    RemoteCompactionV2Response(
        compactionOutput = compactionOutput,
        completedResponse = usage?.let { Response(id = "compact_response", usage = it, endTurn = true) },
    )

private fun assertAdvancedCompactionCheckpoint(
    checkpoint: CompactionCheckpoint,
    prefix: List<ResponseItem.HistoryItem>,
    historyBaseIndex: Int,
    previousCheckpoint: CompactionCheckpoint,
) {
    assertEquals(prefix, checkpoint.prefix)
    assertEquals(historyBaseIndex, checkpoint.historyBaseIndex)
    assertEquals(previousCheckpoint.windowNumber + 1, checkpoint.windowNumber)
    assertEquals(previousCheckpoint.firstWindowId, checkpoint.firstWindowId)
    assertEquals(previousCheckpoint.windowId, checkpoint.previousWindowId)
    assertTrue(checkpoint.windowId != previousCheckpoint.windowId)
    assertEquals('7', checkpoint.windowId[14])
}
