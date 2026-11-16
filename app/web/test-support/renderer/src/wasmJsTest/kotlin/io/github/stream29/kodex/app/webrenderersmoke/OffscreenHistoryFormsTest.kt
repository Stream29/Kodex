package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryFormRenderer
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryContentRenderer
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlin.test.*
import kotlin.math.abs
import kotlin.time.Duration.Companion.microseconds

val offscreenHistoryFormsTest by testSuite {
    for (width in listOf(160, 360)) for (scale in listOf(1f, 1.5f)) {
        test("History answer marks preserve structured values without changing generic forms width=$width scale=$scale") { offscreenTest {
            val longAnswer = "Long answer with Unicode 路径 " + "continued ".repeat(12)
            val event = StableAskUserEvent(
                askId = 1,
                arguments = RequestUserInputArgs(listOf(
                    RequestUserInputQuestion("q", "Layout", "Choose layout", options = listOf(
                        RequestUserInputQuestionOption("[Visible]", "Keep this description"),
                        RequestUserInputQuestionOption(longAnswer, "Long option description"),
                    )),
                    RequestUserInputQuestion("secret", "Secret", "Enter secret", isSecret = true),
                )),
                response = RequestUserInputResponse(mapOf(
                    "q" to RequestUserInputAnswer(listOf("[Visible]", longAnswer, "user_note: keep [brackets]")),
                    "secret" to RequestUserInputAnswer(listOf("RAW_SECRET_MUST_NOT_APPEAR")),
                )),
            )
            val scene = OffscreenScene.create(width = width, height = 3000) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle { scene.hasText("[hidden]") && scene.hasText(longAnswer) }
                assertTrue(scene.hasText("[Visible]"), "Selected original must remain visible")
                assertTrue(scene.hasText("keep [brackets]"), "Free-text brackets must remain visible")
                assertTrue(scene.hasText("Other"), "Other must remain visible")
                assertFalse(scene.hasText("RAW_SECRET_MUST_NOT_APPEAR"), "Secret must not be projected")
                val marks = scene.nodes().filter {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "history-answer-mark"
                }.sortedBy { it.boundsInRoot.top }
                assertEquals(3, marks.size)
                val answers = listOf("[Visible]", longAnswer, "Other").map { scene.text(it).boundsInRoot }
                for ((index, pair) in marks.zip(answers).withIndex()) {
                    val (mark, answer) = pair
                    assertTrue(mark.boundsInRoot.right <= answer.left, "Mark $index overlaps answer: ${mark.boundsInRoot} vs $answer")
                    assertTrue(abs(mark.boundsInRoot.top - answer.top) <= 1f, "Mark $index vertical mismatch: ${mark.boundsInRoot} vs $answer")
                }
                val selected = scene.nodes().filter {
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "Selected"
                }
                assertEquals(3, selected.size)
                assertTrue(selected.all { it.config.getOrNull(SemanticsActions.OnClick) == null }, "Selected answers must not be clickable")
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(longAnswer).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                assertTrue(layouts.single().lineCount > 1, "Long answer must wrap")
                assertEquals(longAnswer, layouts.single().layoutInput.text.text)
            } finally { scene.close() }

            val generic = OffscreenScene.create(width = width, height = 3000) {
                HistoryRequestUserInputContent(event, null, rememberComposeHistoryFormRenderer())
            }
            try {
                generic.settle { generic.hasText(longAnswer) }
                assertFalse(generic.nodes().any {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "history-answer-mark"
                })
            } finally { generic.close() }
        } }
    }
    test("visible History answer mark is measured in a short viewport") { offscreenTest {
        val event = StableAskUserEvent(
            askId = 2,
            arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion(
                "q", "Layout", "Choose", options = listOf(RequestUserInputQuestionOption("Compact", "Small")),
            ))),
            response = RequestUserInputResponse(mapOf(
                "q" to RequestUserInputAnswer(listOf("Compact")),
            )),
        )
        val scene = OffscreenScene.create(width = 160, height = 240) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                HistoryEventContent(event, rememberComposeHistoryContentRenderer())
            }
        }
        try {
            scene.settle { scene.hasText("Compact") }
            val mark = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "history-answer-mark"
            }.boundsInRoot
            val answer = scene.text("Compact").boundsInRoot
            assertTrue(mark.width > 0f && mark.height > 0f)
            assertTrue(mark.right <= answer.left && abs(mark.top - answer.top) <= 1f)
            assertTrue(answer.bottom <= 240f)
        } finally { scene.close() }
    } }
    test("completed answers preserve question order, selected descriptions and one Other with multiline feedback") { offscreenTest {
        val event = StableAskUserEvent(
            askId = 3,
            arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("q", "Layout", "Choose layout", options = listOf(
                    RequestUserInputQuestionOption("Compact", "Use less space"),
                )),
                RequestUserInputQuestion("free", "Notes", "Additional notes"),
            )),
            response = RequestUserInputResponse(mapOf(
                "q" to RequestUserInputAnswer(listOf("Compact", "user_note: Dense\nKeep indent", "user_note: More")),
                "free" to RequestUserInputAnswer(listOf("user_note: raw\n\n  tail")),
                "unknown" to RequestUserInputAnswer(listOf("Do not invent another question")),
            )),
        )
        val expected = listOf(
            "Layout: Choose layout +2ms", "Compact", "Use less space", "Other",
            "Dense\nKeep indent", "More", "Notes: Additional notes", "raw\n\n  tail",
        )
        val rows = event.requestUserInputHistoryRows(1_500.microseconds)
        assertEquals(expected, rows.map { it.value })
        assertEquals(HistoryFormValueKind.SelectedOption, rows[1].kind)
        assertEquals(HistoryFormValueKind.OptionDescription, rows[2].kind)
        assertEquals(HistoryFormValueKind.FreeText, rows[4].kind)
        val scene = OffscreenScene.create {
            HistoryRequestUserInputContent(event, 1_500.microseconds, rememberComposeHistoryFormRenderer())
        }
        try {
            scene.settle { expected.all(scene::hasText) }
            assertEquals(expected, expected.sortedBy { scene.text(it).boundsInWindow.top })
            assertTrue(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.StateDescription) == "Selected" &&
                    it.config.getOrNull(SemanticsActions.OnClick) == null
            })
            assertFalse(scene.hasText("Do not invent another question"))
            scene.frame()
        } finally { scene.close() }
    } }

    test("secret answer is redacted before even the renderer port sees a row") { offscreenTest {
        val secret = "do-not-leak\nsecret"
        val event = StableAskUserEvent(
            askId = 4,
            arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("secret", "Password", "Enter password", isSecret = true),
            )),
            response = RequestUserInputResponse(mapOf(
                "secret" to RequestUserInputAnswer(listOf(secret)),
            )),
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
            scene.settle { scene.hasText("[hidden]") }
            assertTrue(seen.isNotEmpty())
            assertFalse(seen.any { it.contains("do-not-leak") || it.contains("secret") })
            scene.frame()
        } finally { scene.close() }
    } }

    test("empty question list keeps its heading") { offscreenTest {
        val event = StableAskUserEvent(
            askId = 5,
            arguments = RequestUserInputArgs(emptyList()),
            response = RequestUserInputResponse(emptyMap()),
        )
        val scene = OffscreenScene.create {
            HistoryRequestUserInputContent(event, null, rememberComposeHistoryFormRenderer())
        }
        try {
            scene.settle { scene.hasText("Ask the user") }
            assertEquals(1, event.requestUserInputHistoryRows().size)
            scene.frame()
        } finally { scene.close() }
    } }
}
