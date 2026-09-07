package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestSubagentTaskPanelTest {
    @Test
    fun longTasksCanBeScrolledToAcceptAndClicked() = verifyDecision(accepted = true)

    @Test
    fun longTasksCanBeScrolledToRejectAndClicked() = verifyDecision(accepted = false)

    @Test
    fun rejectionAllowsEmptyMessage() = verifyDecision(accepted = false, feedback = "")

    @Test
    fun rejectionInputAcceptsTyping() = verifyDecision(accepted = false, feedback = "", typeMessage = true)

    private fun verifyDecision(
        accepted: Boolean,
        feedback: String = "Optional user message",
        typeMessage: Boolean = false,
    ) = runTest {
        val pending = SuggestSubagentTaskState.Pending(
            callId = "long-tasks",
            arguments = SuggestSubagentTaskArgs(
                List(4) { index ->
                    SuggestedSubagentTask("Task $index", "Long task instructions\n".repeat(20))
                },
            ),
            configuration = SuggestedSessionConfiguration(
                OpenAiModelId("test"), ReasoningEffort.Low, ServiceTier.Default,
                Path("."), RequestUserInputMode.AskUser,
            ),
            feedback = feedback,
        )
        var submittedDecision: Boolean? = null
        val model = object : SuggestSubagentTaskViewModel {
            override val state = MutableStateFlow<SuggestSubagentTaskState>(pending)
            override fun updateFeedback(callId: String, text: String): Boolean {
                val current = state.value as SuggestSubagentTaskState.Pending
                state.value = current.copy(feedback = text, revision = current.revision + 1)
                return true
            }
            override fun updateConfiguration(callId: String, configuration: SuggestedSessionConfiguration) = false
            override suspend fun submit(
                callId: String,
                expectedRevision: Long,
                accepted: Boolean,
            ): SuggestSubagentTaskSubmissionResult {
                assertEquals(pending.callId, callId)
                val current = state.value as SuggestSubagentTaskState.Pending
                assertEquals(current.revision, expectedRevision)
                submittedDecision = accepted
                state.value = current.copy(submitting = true)
                return SuggestSubagentTaskSubmissionResult.Submitted
            }
            override fun close() = Unit
        }
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val current by model.state.collectAsState()
                SuggestSubagentTaskPanel(
                    model, current as SuggestSubagentTaskState.Pending, columns = 64, rows = 12,
                    configurationContent = {},
                )
            }
            assertTrue("Task 0\nLong task instructions" in initial, initial)
            repeat(100) {
                sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            }
            advanceUntilIdle()
            val rendered = awaitSnapshot()
            val lines = rendered.lines()
            val label = if (accepted) "[○ Accept]" else "[○ Reject]"
            val row = lines.indexOfFirst { label in it }
            assertTrue(row in 0..11, rendered)
            assertTrue("[○ Accept]" in rendered, rendered)
            assertTrue("[○ Reject]" in rendered, rendered)
            assertFalse("Optional user message" in rendered, rendered)
            assertTrue(
                lines.indexOfFirst { "[○ Accept]" in it } <
                    lines.indexOfFirst { "[○ Reject]" in it },
                rendered,
            )
            val column = lines[row].indexOf(label) + 1
            sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
            awaitSnapshot()
            advanceUntilIdle()
            if (!accepted) {
                var expanded = awaitSnapshot()
                repeat(3) {
                    if ("  >" !in expanded) expanded = awaitSnapshot()
                }
                assertNull(submittedDecision)
                assertTrue("[● Reject]" in expanded, expanded)
                if (feedback.isNotEmpty()) assertTrue(feedback in expanded, expanded)
                if (typeMessage) {
                    sendKeyEvent(KeyboardEvent(codepoint = 'x'.code))
                    awaitSnapshot()
                    advanceUntilIdle()
                    assertEquals("x", (model.state.value as SuggestSubagentTaskState.Pending).feedback)
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                advanceUntilIdle()
            }
            assertEquals(accepted, submittedDecision)
        }
    }
}
