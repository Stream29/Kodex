package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryFormRenderer
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.tool.multiagent.*
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlin.test.*
import kotlin.time.Duration.Companion.microseconds

val offscreenHistoryFormsTest by testSuite {
    test("completed answers preserve question order, selected descriptions and one Other with multiline feedback") { offscreenTest {
        val event = StableRequestUserInputToolEvent(
            "answered",
            arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("q", "Layout", "Choose layout", options = listOf(
                    RequestUserInputQuestionOption("Compact", "Use less space"),
                )),
                RequestUserInputQuestion("free", "Notes", "Additional notes"),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "q" to RequestUserInputAnswer(listOf("Compact", "user_note: Dense\nKeep indent", "user_note: More")),
                "free" to RequestUserInputAnswer(listOf("user_note: raw\n\n  tail")),
                "unknown" to RequestUserInputAnswer(listOf("Do not invent another question")),
            ))),
        )
        val expected = listOf(
            "Layout: Choose layout +2ms", "[● Compact]", "  Use less space", "[● Other]",
            "  > Dense", "    Keep indent", "  > More",
            "Notes: Additional notes", "  > raw", "    ", "      tail",
        )
        assertEquals(expected, event.requestUserInputHistoryRows(1_500.microseconds).map { it.value })
        val scene = OffscreenScene.create {
            HistoryRequestUserInputContent(event, 1_500.microseconds, rememberComposeHistoryFormRenderer())
        }
        try {
            scene.settle { expected.all(scene::hasText) }
            assertEquals(expected, expected.sortedBy { scene.text(it).boundsInWindow.top })
            assertFalse(scene.hasText("Do not invent another question"))
            scene.frame()
        } finally { scene.close() }
    } }

    test("secret answer is redacted before even the renderer port sees a row") { offscreenTest {
        val secret = "do-not-leak\nsecret"
        val event = StableRequestUserInputToolEvent(
            "secret", arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("secret", "Password", "Enter password", isSecret = true),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "secret" to RequestUserInputAnswer(listOf(secret)),
            ))),
        )
        val seen = mutableListOf<String>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeHistoryFormRenderer()
            HistoryRequestUserInputContent(event, null, object : HistoryFormRenderer by graphical {
                @Composable override fun Row(row: RequestUserInputHistoryRowModel) {
                    seen.add(row.value)
                    graphical.Row(row)
                }
            })
        }
        try {
            scene.settle { scene.hasText("  > [hidden]") }
            assertTrue(seen.isNotEmpty())
            assertFalse(seen.any { it.contains("do-not-leak") || it.contains("secret") })
            scene.frame()
        } finally { scene.close() }
    } }

    for (failed in listOf(false, true)) {
        test("empty question list keeps its heading and exact result failed=$failed") { offscreenTest {
            val event = StableRequestUserInputToolEvent(
                "empty", arguments = RequestUserInputArgs(emptyList()),
                result = if (failed) StableRequestUserInputResult.Failure("No answer")
                else StableRequestUserInputResult.Answered(RequestUserInputResponse(emptyMap())),
            )
            val scene = OffscreenScene.create {
                HistoryRequestUserInputContent(event, null, rememberComposeHistoryFormRenderer())
            }
            try {
                scene.settle { scene.hasText("Ask the user") && (!failed || scene.hasText("Failed to submit: No answer")) }
                assertEquals(if (failed) 2 else 1, event.requestUserInputHistoryRows().size)
                if (failed) assertEquals(RequestUserInputHistoryRowRole.Error, event.requestUserInputHistoryRows().last().role)
                scene.frame()
            } finally { scene.close() }
        } }
    }

    val results = listOf(
        StableSuggestSubagentTaskResult.Completed(SuggestSubagentTaskResponse.Accepted(null, listOf(
            SuggestedSessionMeta("file:///fixture/first", "Different metadata name"),
            SuggestedSessionMeta("file:///fixture/second", "Worker"),
        ))),
        StableSuggestSubagentTaskResult.Completed(SuggestSubagentTaskResponse.Rejected(" raw\n\n  feedback ")),
        StableSuggestSubagentTaskResult.Failure("Stopped by hook"),
    )
    for ((index, result) in results.withIndex()) {
        test("suggestion result is read only and keeps task identity branch=$index") { offscreenTest {
            val event = StableSuggestSubagentTaskToolEvent("suggest", arguments = SuggestSubagentTaskArgs(listOf(
                SuggestedSubagentTask("First", "Inspect all tests.\nKeep details."),
                SuggestedSubagentTask("Second", "Review the full implementation."),
            )), result = result)
            val rows = event.suggestSubagentTaskHistoryRows()
            val scene = OffscreenScene.create {
                HistorySuggestionContent(event, null, rememberComposeHistoryFormRenderer())
            }
            try {
                scene.settle { rows.all { scene.hasText(it.value) } }
                assertEquals(rows.map { it.value }, rows.sortedBy { scene.text(it.value).boundsInWindow.top }.map { it.value })
                assertFalse(scene.hasText("Different metadata name"))
                when (index) {
                    0 -> assertEquals(listOf(
                        "Suggested Sessions", "First", "file:///fixture/first", "Inspect all tests.\nKeep details.",
                        "Second", "file:///fixture/second", "Review the full implementation.", "[● Accept]",
                    ), rows.map { it.value })
                    1 -> {
                        assertTrue(scene.hasText("[● Reject]"))
                        assertEquals(listOf("  >  raw", "    ", "      feedback "), rows.takeLast(3).map { it.value })
                        assertFalse(rows.any { it.value.startsWith("file:") })
                    }
                    else -> {
                        assertEquals(RequestUserInputHistoryRowRole.Error, rows.last().role)
                        assertEquals("Failed to submit: Stopped by hook", rows.last().value)
                        assertFalse(rows.any { it.value.startsWith("[●") || it.value.startsWith("file:") })
                    }
                }
                scene.frame()
            } finally { scene.close() }
        } }
    }
}
