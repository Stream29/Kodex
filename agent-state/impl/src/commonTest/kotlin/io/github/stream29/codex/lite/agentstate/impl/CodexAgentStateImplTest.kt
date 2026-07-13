package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateEnum
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.CompactionResponse
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.Response
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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
    fun defaultSettingsMatchRustCompactionDefaults() {
        val settings = CodexAgentSettings(OpenAiModelId("test-model"))

        assertEquals(true, settings.remoteCompactionV2)
        assertEquals(null, settings.autoCompactionTokenLimit)
        assertEquals(null, settings.installationId)
        assertEquals(null, settings.sessionId)
        assertEquals(null, settings.threadId)
    }

    @Test
    fun resumeContinuesSamplingWhenEndTurnIsFalse() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentStateImpl(
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
        assertEquals(1, agent.appendResponseItem(user, instant(0), tokenCount = 1))

        agent.resume().toList()

        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(2, requests.size)
        assertEquals(listOf(user), requests[0].input)
        assertEquals(
            listOf(
                user,
                ResponseItem.Reasoning(summary = emptyList()),
                assistantMessage("Preparing the answer."),
            ),
            requests[1].input,
        )
        assertEquals(6, storage.latestIndex())
        assertIs<ResponseItem.Reasoning>(storage.history[2])
        assertEquals(assistantMessage("Done."), storage.history[5])
        assertEquals(null, storage.history.nextIndex(6))
        assertEquals(13, storage.tokenCount[6])
        assertTrue(storage.timestamp[4] > instant(0))
        assertEquals(6, agent.latestIndex.value)
    }

    @Test
    fun resumeSendsStoredReasoningItemsBackToModel() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentStateImpl(
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
        val user = userMessage("Continue.")
        val reasoning = ResponseItem.Reasoning(summary = emptyList())
        val assistant = assistantMessage("Ready.")

        agent.appendResponseItem(user, instant(0), tokenCount = null)
        agent.appendResponseItem(reasoning, instant(1), tokenCount = null)
        agent.appendResponseItem(assistant, instant(2), tokenCount = null)

        agent.resume().toList()

        assertEquals(
            listOf(user, reasoning, assistant),
            requests.single().input,
        )
    }

    @Test
    fun resumePublishesOutputItemAndRawStreamEventsBeforeCompleted() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
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
        val agent = CodexAgentStateImpl(
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

        agent.appendResponseItem(userMessage("Start streaming."), instant(0), tokenCount = null)
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.resume().toList()
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
    fun cancellingResumeAfterDeltaResetsStateAndDoesNotPersistPartialText() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val deltaEvent = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "partial",
        )
        val deltaCollected = CompletableDeferred<Unit>()
        val releaseStream = CompletableDeferred<Unit>()
        val agent = CodexAgentStateImpl(
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

        agent.appendResponseItem(user, instant(0), tokenCount = null)
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.resume().toList(collected)
        }

        deltaCollected.await()
        assertEquals(listOf<ResponsesStreamEvent>(deltaEvent), collected)

        runningResume.cancel(CancellationException("runtime interrupted stream"))
        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(1, storage.latestIndex())
        assertEquals(user, storage.history[1])
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(-1, storage.tokenCount.latestIndex())
    }

    @Test
    fun resumePropagatesCancellationExceptionWithoutWrappingIt() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentStateImpl(
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

        agent.appendResponseItem(user, instant(0), tokenCount = null)
        val exception = assertFailsWith<CancellationException> {
            agent.resume().toList()
        }

        assertEquals("transport cancelled", exception.message)
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(1, storage.latestIndex())
        assertEquals(user, storage.history[1])
    }

    @Test
    fun cancellingResumeFromOutputItemCollectorKeepsStableHistoryItem() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val outputItem = assistantMessage("Stable output.")
        val outputEvent = ResponsesStreamEvent.OutputItemDone(
            outputIndex = 0,
            item = outputItem,
        )
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(outputEvent)
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendResponseItem(userMessage("Start."), instant(0), tokenCount = null)
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.resume().collect { event ->
                collected += event
                currentCoroutineContext().cancel(CancellationException("runtime cancelled after stable item"))
            }
        }

        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(listOf<ResponsesStreamEvent>(outputEvent), collected)
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(outputItem, storage.history[2])
        assertEquals(null, storage.history.nextIndex(3))
    }

    @Test
    fun cancellingResumeFromCompletedCollectorKeepsStableTokenCount() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
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
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(completedEvent)
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendResponseItem(userMessage("Start."), instant(0), tokenCount = null)
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.resume().collect { event ->
                collected += event
                currentCoroutineContext().cancel(CancellationException("runtime cancelled after completion"))
            }
        }

        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(listOf<ResponsesStreamEvent>(completedEvent), collected)
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(9, storage.tokenCount[2])
    }

    @Test
    fun resumePersistsToolCallOutputItem() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val toolCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = """{"cmd":"date"}""",
            callId = "call_1",
        )
        val agent = CodexAgentStateImpl(
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

        agent.appendResponseItem(userMessage("What time is it?"), instant(0), tokenCount = null)
        agent.resume().toList()

        assertEquals(1, requests.size)
        assertEquals(2, storage.latestIndex())
        assertEquals(toolCall, storage.history[2])
        assertEquals(-1, storage.tokenCount.latestIndex())
    }

    @Test
    fun updateSettingPublishesStateIndexWithoutHistoryItem() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("old-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentStateImpl(
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

        agent.appendResponseItem(user, instant(0), tokenCount = 1)
        val settingsIndex = agent.updateSetting(
            settings = CodexAgentSettings(OpenAiModelId("new-model")),
            timestamp = instant(1),
            tokenCount = 2,
        )

        assertEquals(2, settingsIndex)
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(OpenAiModelId("new-model"), storage.settings[2].model)

        agent.resume().toList()

        assertEquals(OpenAiModelId("new-model"), requests.single().model)
        assertEquals(listOf(user), requests.single().input)
    }

    @Test
    fun appendPlanUpdatePublishesRelatedTimelineBeforeHistory() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val plan = UpdatePlanArgs(
            plan = listOf(PlanItemArg(step = "inspect", status = StepStatus.InProgress)),
        )
        val planCall = ResponseItem.FunctionCall(
            name = "update_plan",
            arguments = "{}",
            callId = "plan_call",
        )

        agent.appendResponseItem(userMessage("Start."), instant(0), tokenCount = 1)
        val planIndex = agent.appendPlanUpdate(planCall, plan)

        assertEquals(2, planIndex)
        assertEquals(plan, storage.plan[2])
        assertEquals(planCall, storage.history[2])
        assertEquals(1, storage.tokenCount[2])
        assertTrue(storage.timestamp[2] > instant(0))
    }

    @Test
    fun mutationFailsWhenAnotherMutationIsRunning() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val agent = CodexAgentStateImpl(
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

        agent.appendResponseItem(userMessage("Wait."), instant(0), tokenCount = null)
        val runningResume = async { agent.resume().toList() }
        requestStarted.await()

        val exception = assertFailsWith<CodexAgentStateConcurrentModificationException> {
            agent.appendResponseItem(userMessage("Concurrent input."), instant(1), tokenCount = null)
        }
        assertEquals(CodexAgentStateEnum.LlmRequest.Response, exception.currentState)

        releaseResponse.complete(Unit)
        runningResume.await()
    }

    @Test
    fun resumePersistsCompactionOutputWithoutRequestingCompaction() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val compactItem = ResponseItem.ContextCompaction(encryptedContent = "compact")
        val agent = CodexAgentStateImpl(
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
        agent.appendResponseItem(user, instant(0), tokenCount = null)
        agent.resume().toList()

        assertEquals(2, storage.latestIndex())
        assertEquals(compactItem, storage.history[2])
        assertEquals(0, storage.compaction[2].historyBaseIndex)
    }

    @Test
    fun forcedCompactUsesRemoteCompactionV2ByDefault() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val initialCheckpoint = storage.compaction[0]
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        val agent = CodexAgentStateImpl(
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
        agent.appendResponseItem(user, instant(0), tokenCount = null)
        val compactIndex = agent.forcedCompact()

        assertEquals(2, compactIndex)
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertEquals(listOf(user), compactRequest.history)
        assertEquals(RemoteCompactionV2Trigger.Manual, compactRequest.trigger)
        assertEquals(RemoteCompactionV2Reason.UserRequested, compactRequest.reason)
        assertEquals(RemoteCompactionV2Phase.StandaloneTurn, compactRequest.phase)
        assertEquals(null, compactRequest.settings.installationId)
        assertEquals(null, compactRequest.settings.sessionId)
        assertEquals(null, compactRequest.settings.threadId)
        assertEquals(initialCheckpoint, compactRequest.checkpoint)
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "compact"), storage.history[2])
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(user, compaction),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
        assertEquals(11, storage.tokenCount[2])
        assertEquals(2, agent.latestIndex.value)
    }

    @Test
    fun remoteCompactionV2IncludesIdentityMetadataFromSettings() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                installationId = "install",
                sessionId = "session",
                threadId = "thread",
            ),
        )
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.appendResponseItem(userMessage("Compact."), instant(0), tokenCount = null)
        agent.forcedCompact()

        val compactRequest = compactRequests.single()
        assertEquals("install", compactRequest.settings.installationId)
        assertEquals("session", compactRequest.settings.sessionId)
        assertEquals("thread", compactRequest.settings.threadId)
    }

    @Test
    fun remoteCompactionV2UsesWindowNumberFromCheckpoint() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
            settings = CodexAgentSettings(OpenAiModelId("test-model")),
            windowNumber = 7,
        )
        val initialCheckpoint = storage.compaction[0]
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.appendResponseItem(userMessage("Compact."), instant(0), tokenCount = null)
        agent.forcedCompact()

        val compactRequest = compactRequests.single()
        assertEquals(7L, compactRequest.checkpoint.windowNumber)
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(userMessage("Compact."), ResponseItem.Compaction(encryptedContent = "compact")),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
    }

    @Test
    fun forcedCompactCanUseLegacyCompactResponseWhenRemoteCompactionV2IsDisabled() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                remoteCompactionV2 = false,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val compactSummary = ResponseItem.CompactionSummary(encryptedContent = "summary")
        val compactRequests = mutableListOf<CompactionInput>()
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                compactResponse { request ->
                    compactRequests += request
                    OpenAiResult.Success(
                        CompactionResponse(output = listOf(compactSummary)),
                    )
                }
            },
            storage = storage,
        )

        val user = userMessage("This context is too large.")
        agent.appendResponseItem(user, instant(0), tokenCount = null)
        val compactIndex = agent.forcedCompact()

        assertEquals(2, compactIndex)
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
        assertEquals(1, compactRequests.size)
        assertEquals(OpenAiModelId("test-model"), compactRequests.single().model)
        assertEquals(listOf(user, ResponseItem.CompactionTrigger), compactRequests.single().input)
        assertEquals(ResponseItem.ContextCompaction(), storage.history[2])
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(compactSummary),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
        assertEquals(2, agent.latestIndex.value)
    }

    @Test
    fun resumeRunsPreTurnRemoteCompactionBeforeSamplingWhenTokenLimitIsReached() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
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
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(90, 1, 91),
                    )
                }

                createResponseWithHeaders { request, extraHeaders ->
                    responseRequests += RecordedCreateResponse(request, extraHeaders)
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, final),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )

        agent.appendResponseItem(user, instant(0), tokenCount = 90)
        agent.resume().toList()

        assertEquals(1, compactRequests.size)
        assertEquals(listOf(user), compactRequests.single().history)
        assertEquals(RemoteCompactionV2Phase.PreTurn, compactRequests.single().phase)
        assertEquals(1, responseRequests.size)
        assertEquals(listOf(user, compaction), responseRequests.single().request.input)
        assertEquals(emptyMap(), responseRequests.single().extraHeaders)
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "pre-turn-compact"), storage.history[2])
        assertEquals(final, storage.history[3])
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(user, compaction),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
    }

    @Test
    fun resumeRunsMidTurnRemoteCompactionThenContinuesSamplingWhenFollowUpIsNeeded() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
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
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(20, 1, 21),
                    )
                }

                createResponseWithHeaders { request, extraHeaders ->
                    responseRequests += RecordedCreateResponse(request, extraHeaders)
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

        agent.appendResponseItem(user, instant(0), tokenCount = 1)
        agent.resume().toList()

        assertEquals(2, responseRequests.size)
        assertEquals(listOf(user), responseRequests[0].request.input)
        assertEquals(emptyMap(), responseRequests[0].extraHeaders)
        assertEquals(1, compactRequests.size)
        assertEquals(listOf(user, partial), compactRequests.single().history)
        assertEquals(RemoteCompactionV2Phase.MidTurn, compactRequests.single().phase)
        assertEquals(listOf(user, compaction), responseRequests[1].request.input)
        assertEquals(emptyMap(), responseRequests[1].extraHeaders)
        assertEquals(partial, storage.history[2])
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "mid-turn-compact"), storage.history[4])
        assertEquals(final, storage.history[5])
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[4],
            prefix = listOf(user, compaction),
            historyBaseIndex = 5,
            previousCheckpoint = initialCheckpoint,
        )
    }

    @Test
    fun resumeDoesNotCompactAfterFinalResponseUntilNextResume() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
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
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(20, 1, 21),
                    )
                }

                createResponseWithHeaders { request, extraHeaders ->
                    responseRequests += RecordedCreateResponse(request, extraHeaders)
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

        agent.appendResponseItem(user, instant(0), tokenCount = 1)
        agent.resume().toList()

        assertEquals(1, responseRequests.size)
        assertEquals(emptyMap(), responseRequests.single().extraHeaders)
        assertEquals(0, compactRequests.size)
        assertEquals(firstFinal, storage.history[2])
        assertEquals(20, storage.tokenCount[3])

        agent.resume().toList()

        assertEquals(2, responseRequests.size)
        assertEquals(1, compactRequests.size)
        assertEquals(listOf(user, firstFinal), compactRequests.single().history)
        assertEquals(RemoteCompactionV2Phase.PreTurn, compactRequests.single().phase)
        assertEquals(listOf(user, compaction), responseRequests[1].request.input)
        assertEquals(secondFinal, storage.history[5])
    }

    @Test
    fun remoteCompactionV2ClientFailureDoesNotMutateStorage() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response {
                    throw IllegalStateException("bad remote compaction v2")
                }
            },
            storage = storage,
        )

        agent.appendResponseItem(userMessage("Compact."), instant(0), tokenCount = null)

        assertFailsWith<IllegalStateException> {
            agent.forcedCompact()
        }
        assertEquals(1, storage.latestIndex())
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
    }

    @Test
    fun forcedCompactFailsWhenCompactionRequestFails() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                remoteCompactionV2 = false,
            ),
        )
        val error = OpenAiErrorResponse(message = "forced compact failed")
        val agent = CodexAgentStateImpl(
            client = mockOpenAiClient {
                compactResponse {
                    OpenAiResult.Failure(error)
                }
            },
            storage = storage,
        )

        agent.appendResponseItem(userMessage("This context is too large."), instant(0), tokenCount = null)

        val exception = assertFailsWith<CodexCompactionFailureException> {
            agent.forcedCompact()
        }
        assertEquals(error, exception.error)
        assertEquals(1, storage.latestIndex())
        assertEquals(CodexAgentStateEnum.Idle, agent.state.value)
    }
}

private data class RecordedCreateResponse(
    val request: ResponsesApiRequest,
    val extraHeaders: Map<String, String>,
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

private suspend fun InMemoryCodexAgentStorage.initialize(
    settings: CodexAgentSettings,
    windowNumber: Long = 0,
    windowId: String = "window-$windowNumber",
    firstWindowId: String = windowId,
    previousWindowId: String? = null,
) {
    this.settings[0] = settings
    this.compaction[0] = CompactionCheckpoint(
        prefix = emptyList(),
        historyBaseIndex = 0,
        windowNumber = windowNumber,
        firstWindowId = firstWindowId,
        previousWindowId = previousWindowId,
        windowId = windowId,
    )
    this.plan[0] = UpdatePlanArgs(plan = emptyList())
}
