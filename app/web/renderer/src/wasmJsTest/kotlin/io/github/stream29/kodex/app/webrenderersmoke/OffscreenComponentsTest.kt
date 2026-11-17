package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiondelete.SessionDeleteContent
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.history.HistoryMessageContent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.cli.history.HistoryStreamingTextContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.TimeSource

val offscreenComponentsTest by testSuite {
    test("long streaming prose preserves every Unicode delta without one semantics node per line") { offscreenTest {
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 256)
        val chunk = "你好 — https://example.invalid/reference\n".repeat(12)
        val expected = chunk.repeat(128)
        var visualUpdates = 0
        val started = TimeSource.Monotonic.markNow()
        val output = OffscreenScene.create {
            HistoryStreamingTextContent(HistoryStreamingKind.Message, events, { visualUpdates++ })
        }
        try {
            output.settle { output.hasText("Assistant responding") }
            withTimeout(5_000) { events.subscriptionCount.first { it == 1 } }
            repeat(128) { events.emit(ResponsesStreamEvent.OutputTextDelta("long-output", 0, 0, chunk)) }
            output.settle("all long-output deltas") { output.hasText(expected) }
            assertTrue(visualUpdates > 0)
            assertTrue(output.nodes().size < 30, "Text lines must not become thousands of separate semantics nodes.")
            assertEquals(expected, output.text(expected).config[SemanticsProperties.Text].single().text)
            val png = output.frame()
            println("OFFSCREEN_LONG_OUTPUT_SAMPLE chars=${expected.length} deltas=128 visualUpdates=$visualUpdates " +
                "semanticsNodes=${output.nodes().size} pngBytes=${png.size} elapsed=${started.elapsedNow()}")
        } finally { output.close() }
    } }

    test("disabled action rejects direct and retained accessibility callbacks") { offscreenTest {
        val enabled = mutableStateOf(false)
        var calls = 0
        val output = OffscreenScene.create { UiAction("Controlled action", { calls++ }, enabled = enabled.value) }
        try {
            output.settle { output.hasText("Controlled action") }
            fun action() = assertNotNull(output.nodes().firstNotNullOfOrNull {
                it.config.getOrNull(SemanticsActions.OnClick)?.action
            })
            val retained = action()
            retained()
            assertEquals(0, calls, "A direct semantics callback cannot bypass disabled admission.")
            enabled.value = true
            output.settle { output.nodes().none { it.config.contains(SemanticsProperties.Disabled) } }
            retained()
            assertEquals(1, calls)
            enabled.value = false
            output.settle { output.nodes().any { it.config.contains(SemanticsProperties.Disabled) } }
            retained()
            assertEquals(1, calls, "A callback retained by the browser listener must observe current admission.")
        } finally { output.close() }
    } }
    test("asynchronous composition failures fail the scene rather than becoming a successful empty frame") { offscreenTest {
        val expected = IllegalStateException("scene effect failure")
        val output = OffscreenScene.create {
            LaunchedEffect(Unit) { throw expected }
            UiText("Before failure")
        }
        try {
            val failure = assertFailsWith<AssertionError> { output.settle { true } }
            assertSame(expected, failure.cause)
        } finally { output.close() }
    } }

    test("shared widgets render a raster PNG and pointer interaction changes the exact state and image") { offscreenTest {
        val count = mutableIntStateOf(0)
        val output = OffscreenScene.create {
            UiColumn {
                UiAction("Advance", onClick = { count.intValue++ })
                UiText("Count ${count.intValue}")
            }
        }
        try {
            output.settle { output.hasText("Count 0") }
            val before = output.frame()
            output.click("Advance")
            output.settle { output.hasText("Count 1") }
            assertEquals(1, count.intValue)
            assertFalse(before.contentEquals(output.frame()))
        } finally { output.close() }
    } }
    test("shared Delete content cancels without writing and deletes only its captured target") { offscreenTest {
        val targets = mutableListOf<Int>()
        var cancels = 0
        var result: Boolean? = null
        val child = createSessionDeleteViewModel(41, "captured", SessionDeleteDependencies { targets += it; true })
        val output = OffscreenScene.create {
            SessionDeleteContent(child, onCancel = { cancels++ }, onResult = { result = it })
        }
        try {
            output.settle { output.hasText("Delete captured?") }
            output.click("Cancel")
            assertEquals(1, cancels)
            assertEquals(emptyList(), targets)
            output.click("Delete")
            output.settle { result != null }
            assertEquals(listOf(41), targets)
            assertEquals(true, result)
        } finally { output.close() }
        assertFalse(child.isActive)
    } }
    test("shared History message content draws through the graphical leaves without an application or RPC") { offscreenTest {
        val message = StableUserMessage(listOf(ContentItem.InputText("Offscreen message")))
        val output = OffscreenScene.create { HistoryMessageContent(message) }
        try {
            output.settle { output.hasText("User") && output.hasText("Offscreen message") }
            val title = output.text("User").boundsInRoot
            val body = output.text("Offscreen message").boundsInRoot
            assertTrue(title.width > 0 && body.width > 0)
            assertTrue(body.top >= title.bottom)
            output.frame()
        } finally { output.close() }
    } }
}
