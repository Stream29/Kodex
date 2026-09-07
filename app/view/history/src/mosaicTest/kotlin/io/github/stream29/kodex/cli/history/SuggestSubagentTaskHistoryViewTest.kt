package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestSubagentTaskHistoryViewTest {
    @Test
    fun acceptedShowsBoldNamesThenUrisThenFullPrompts() = runTest {
        val event = event(StableSuggestSubagentTaskResult.Completed(
            SuggestSubagentTaskResponse.Accepted(null, listOf(
                SuggestedSessionMeta("file:///tmp/1", "Worker"),
                SuggestedSessionMeta("file:///tmp/2", "Worker"),
            )),
        ))
        val rows = event.suggestSubagentTaskHistoryRows()
        assertEquals(listOf(
            "Suggested Sessions", "Worker", "file:///tmp/1", "Inspect all tests.",
            "Worker", "file:///tmp/2", "Review the full implementation.", "[● Accept]",
        ), rows.map { it.value })
        assertEquals(RequestUserInputHistoryRowRole.Header, rows[1].role)
        assertEquals(RequestUserInputHistoryRowRole.Body, rows[3].role)
        runMosaicTest {
            val rendered = setContentAndSnapshot { Box(Modifier.width(22)) { event.render() } }
            assertTrue(rendered.contains("Suggested Sessions"))
            assertTrue(rendered.contains("file:///tmp/1"))
            assertTrue(rendered.contains("[● Accept]"))
            assertFalse(rendered.contains("[● Reject]"))
        }
    }

    @Test
    fun rejectionAndFailureAreReadonlyAndDoNotInventSuccess() {
        for (feedback in listOf(null, "Narrow scope\nUse tests only")) {
            val rows = event(StableSuggestSubagentTaskResult.Completed(
                SuggestSubagentTaskResponse.Rejected(feedback),
            )).suggestSubagentTaskHistoryRows()
            assertEquals(if (feedback == null) "[● Reject]" else "    Use tests only", rows.last().value)
            assertFalse(rows.any { it.value.startsWith("file:") })
            if (feedback != null) assertTrue(rows.any { it.value == "  > Narrow scope" })
        }
        val rows = event(StableSuggestSubagentTaskResult.Failure("Stopped by hook")).suggestSubagentTaskHistoryRows()
        assertEquals("Failed to submit: Stopped by hook", rows.last().value)
        assertEquals(RequestUserInputHistoryRowRole.Error, rows.last().role)
        assertFalse(rows.any { it.value.startsWith("[●") || it.value.startsWith("file:") })
        assertTrue(rows.any { it.value == "Inspect all tests." })
    }

    private fun event(result: StableSuggestSubagentTaskResult) = StableSuggestSubagentTaskToolEvent(
        callId = "suggest",
        arguments = SuggestSubagentTaskArgs(listOf(
            SuggestedSubagentTask("Worker", "Inspect all tests."),
            SuggestedSubagentTask("Worker", "Review the full implementation."),
        )),
        result = result,
    )
}
