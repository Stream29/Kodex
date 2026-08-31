package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

val historyIndexViewModelTest by testSuite {
    test("tracks sparse index entries incrementally and invalidates on revert") {
        coroutineScope {
            val storage = InMemoryKodexAgentStorage(
                KodexAgentSettings(model = OpenAiModelId("test")),
            )
            val latestIndex = MutableStateFlow(0)
            val agentState = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.Empty)
            val childScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val viewModel = HistoryIndexViewModelImpl(
                timeline = storage.index,
                latestIndex = latestIndex,
                agentState = agentState,
                scope = childScope,
            )
            try {
                assertEquals(emptyList(), viewModel.awaitIndexes(emptyList()).indexes)

                storage.index[2] = StableUserMessage(
                    content = listOf(ContentItem.InputText("question\nwith   spaces")),
                )
                latestIndex.value = 3
                val firstWindow = viewModel.awaitIndexes(listOf(2))
                assertEquals(
                    "question with spaces",
                    viewModel.load(firstWindow.generation, 2).summary,
                )

                storage.index[5] = StableAssistantMessage(
                    content = listOf(ContentItem.OutputText("answer")),
                    phase = MessagePhase.FinalAnswer,
                )
                latestIndex.value = 5
                val appended = viewModel.awaitIndexes(listOf(2, 5))
                assertEquals(
                    HistoryIndexEntryKind.UserMessage,
                    viewModel.load(appended.generation, 2).kind,
                )
                assertEquals(
                    HistoryIndexEntryKind.AssistantFinal,
                    viewModel.load(appended.generation, 5).kind,
                )

                storage.revert(5)
                latestIndex.value = 3
                val reverted = viewModel.awaitIndexes(listOf(2))
                assertEquals(1, reverted.generation)

                agentState.value = KodexAgentStateValue.ExternalWrite
                delay(50.milliseconds)
                storage.index.revert(2)
                storage.index[2] = StableUserMessage(
                    content = listOf(ContentItem.InputText("rewritten")),
                )
                agentState.value = KodexAgentStateValue.UserMessage
                val refreshed = viewModel.awaitGenerationAtLeast(2)
                assertEquals(
                    "rewritten",
                    viewModel.load(refreshed.generation, 2).summary,
                )
            } finally {
                childScope.cancelAndJoin()
            }
        }
    }

    test("projects summaries and complete hover details") {
        coroutineScope {
            val storage = InMemoryKodexAgentStorage(
                KodexAgentSettings(model = OpenAiModelId("test")),
            )
            val latestIndex = MutableStateFlow(8)
            val agentState = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.Empty)
            storage.index[1] = CleanCompactionPoint
            storage.index[2] = StableDeveloperMessage(
                content = listOf(
                    ContentItem.InputText("developer"),
                    ContentItem.InputImage("image"),
                ),
            )
            storage.index[3] = StableAgentMessage(
                author = "one",
                recipient = "two",
                content = listOf(
                    AgentMessageInputContent.InputText("private"),
                    AgentMessageInputContent.EncryptedContent("ciphertext"),
                ),
            )
            storage.index[4] = StableRequestUserInputToolEvent(
                callId = "request",
                arguments = RequestUserInputArgs(
                    questions = listOf(
                        RequestUserInputQuestion(
                            id = "secret",
                            header = "Credential",
                            question = "Enter token",
                            isSecret = true,
                            options = listOf(
                                RequestUserInputQuestionOption(
                                    label = "Saved",
                                    description = "Use saved token",
                                ),
                            ),
                        ),
                    ),
                ),
                result = StableRequestUserInputResult.Answered(
                    RequestUserInputResponse(
                        answers = mapOf(
                            "secret" to RequestUserInputAnswer(listOf("do-not-render")),
                        ),
                    ),
                ),
            )
            storage.index[5] = StablePlanUpdate(
                callId = "plan",
                arguments = UpdatePlanArgs(
                    explanation = "Updated",
                    plan = listOf(
                        PlanItemArg("done", StepStatus.Completed),
                        PlanItemArg("current", StepStatus.InProgress),
                        PlanItemArg("later", StepStatus.Pending),
                    ),
                ),
            )
            storage.index[6] = StablePlanUpdate(
                callId = "pending-plan",
                arguments = UpdatePlanArgs(
                    plan = listOf(
                        PlanItemArg("first", StepStatus.Pending),
                        PlanItemArg("second", StepStatus.Pending),
                    ),
                ),
            )
            storage.index[7] = StableUserMessage(content = emptyList())
            storage.index[8] = StableAssistantMessage(
                content = listOf(ContentItem.OutputText("plain")),
            )

            val childScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val viewModel = HistoryIndexViewModelImpl(
                timeline = storage.index,
                latestIndex = latestIndex,
                agentState = agentState,
                scope = childScope,
            )
            try {
                val window = viewModel.awaitIndexes((1..8).toList())
                val generation = window.generation
                assertEquals("Context compacted", viewModel.load(generation, 1).summary)
                assertEquals("developer[image]", viewModel.load(generation, 2).summary)
                assertEquals(
                    "private[encrypted content]",
                    viewModel.load(generation, 3).summary,
                )
                assertEquals("Enter token", viewModel.load(generation, 4).summary)
                assertEquals("current", viewModel.load(generation, 5).summary)
                assertEquals("first", viewModel.load(generation, 6).summary)
                assertEquals("[empty]", viewModel.load(generation, 7).summary)
                assertEquals(
                    HistoryIndexEntryKind.AssistantMessage,
                    viewModel.load(generation, 8).kind,
                )

                val agentDetail = viewModel.loadDetail(generation, 3).content
                assertEquals(
                    "Author: one\nRecipient: two\n\nprivate[encrypted content]",
                    agentDetail,
                )
                val requestDetail = viewModel.loadDetail(generation, 4).content
                assertEquals(true, requestDetail.contains("Enter token"))
                assertEquals(true, requestDetail.contains("[hidden]"))
                assertEquals(false, requestDetail.contains("do-not-render"))
                assertEquals(
                    "Updated\n\n[x] done\n[>] current\n[ ] later",
                    viewModel.loadDetail(generation, 5).content,
                )
            } finally {
                childScope.cancelAndJoin()
            }
        }
    }
}

private suspend fun HistoryIndexViewModelImpl.awaitIndexes(
    expected: List<Int>,
) = withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeout(5.seconds) {
        window.first { state -> state.indexes == expected }
    }
}

private suspend fun HistoryIndexViewModelImpl.awaitGenerationAtLeast(
    expected: Long,
) = withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeout(5.seconds) {
        window.first { state -> state.generation >= expected }
    }
}
