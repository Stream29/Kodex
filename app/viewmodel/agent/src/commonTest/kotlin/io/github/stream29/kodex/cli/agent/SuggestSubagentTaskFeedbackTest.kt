package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

val suggestSubagentTaskFeedbackTest by testSuite {
    listOf(true, false).forEach { accepted ->
        listOf("", "Please revise the task").forEach { feedback ->
            test("accepted=$accepted feedback='$feedback'") {
                coroutineScope {
                    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                    val delegate = repository.open(repository.create()).runtime
                    var completedEvent: StableSuggestSubagentTaskToolEvent? = null
                    val runtime = object : AgentRuntime by delegate {
                        override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
                            completedEvent = assertIs<StableSuggestSubagentTaskToolEvent>(completed)
                            return 0
                        }
                    }
                    var dispatched = false
                    val model = SuggestSubagentTaskViewModelImpl(
                        runtime, this,
                        createSessions = { _, _ -> dispatched = true; emptyList() },
                        resumeRuntime = {},
                        defaultConfiguration = {
                            SuggestedSessionConfiguration(
                                OpenAiModelId("test"), ReasoningEffort.Low, ServiceTier.Default,
                                Path("."), RequestUserInputMode.AskUser,
                            )
                        },
                    )
                    try {
                        model.synchronize(PendingSuggestSubagentTaskToolEvent(
                            callId = "suggestion",
                            arguments = SuggestSubagentTaskArgs(listOf(SuggestedSubagentTask("Task", "Work"))),
                        ))
                        model.updateFeedback("suggestion", feedback)
                        val pending = assertIs<SuggestSubagentTaskState.Pending>(model.state.value)
                        assertEquals(
                            SuggestSubagentTaskSubmissionResult.Submitted,
                            model.submit(pending.callId, pending.revision, accepted),
                        )
                        val response = assertIs<StableSuggestSubagentTaskResult.Completed>(
                            assertNotNull(completedEvent).result,
                        ).response
                        if (accepted) {
                            assertNull(assertIs<SuggestSubagentTaskResponse.Accepted>(response).feedback)
                        } else {
                            assertEquals(
                                feedback.takeIf { it.isNotBlank() },
                                assertIs<SuggestSubagentTaskResponse.Rejected>(response).feedback,
                            )
                        }
                        assertEquals(accepted, dispatched)
                    } finally {
                        model.close()
                        repository.cancelAndJoin()
                    }
                }
            }
        }
    }
}
