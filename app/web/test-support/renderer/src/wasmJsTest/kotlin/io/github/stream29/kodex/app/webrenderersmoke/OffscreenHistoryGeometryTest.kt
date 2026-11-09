@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.history.AgentHistoryContent
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.abs
import kotlin.test.*
import kotlin.time.Instant

val offscreenHistoryGeometryTest by testSuite {
    test("read only entries naturally traverse beyond bounds and page to visible committed rows") { historyGeometryModel(80) { model ->
        val scene = OffscreenScene.create(width = 480, height = 240) {
            AgentHistoryContent(model, rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() }, canEditHistory = false))
        }
        fun focusedEntry() = scene.nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Focused) == true &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("History entry ") } == true
        }
        try {
            scene.settle { scene.hasDescription("History entry 80") }
            scene.key(Key.Tab)
            scene.settle("read only entry takes natural focus") { focusedEntry() != null }
            repeat(16) { scene.key(Key.DirectionUp); scene.advance(); delay(1) }
            scene.settle("directional traversal composes old rows") { !scene.hasDescription("History entry 80") }
            scene.key(Key.PageUp)
            scene.settle("page focuses a fully visible entry") {
                val bounds = focusedEntry()?.boundsInWindow
                bounds != null && bounds.top >= 0 && bounds.bottom <= 240
            }
            val visible = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("History entry ") } == true &&
                    it.positionInRoot.y >= 0 && it.positionInRoot.y + it.size.height <= 240
            }
            assertEquals(visible.minOf { it.positionInRoot.y }, assertNotNull(focusedEntry()).positionInRoot.y,
                "PageUp must focus the visual top in reverse layout")
            assertFalse(model.followsLatest.value)
            assertNull(model.pendingScrollEffect.value)
        } finally { scene.close() }
    } }

    test("sparse product history is bottom aligned in original newest-first window order") { historyGeometryModel(2) { model ->
        val scene = OffscreenScene.create(width = 480, height = 600) {
            Box(Modifier.height(400.dp)) {
                AgentHistoryContent(model, rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() }))
            }
        }
        try {
            scene.settle { scene.hasText("Geometry message 2") && scene.hasText("Geometry message 1") }
            val newest = scene.entryBounds(2)
            val older = scene.entryBounds(1)
            assertEquals(400f, newest.bottom, 1f, "Sparse newest row must touch the viewport bottom: $newest")
            assertTrue(older.bottom <= newest.top + 1f, "Older rows belong above newer rows: $older / $newest")
        } finally { scene.close() }
    } }

    test("Latest waits for the lazy long committed payload and reaches its real bottom") {
        val text = (1..100).joinToString("\n") { "Long committed line $it" }
        historyGeometryModel(2, newestText = text) { model ->
            val acknowledgments = mutableListOf<HistoryScrollEffect>()
            val displayed = object : AgentHistoryViewModel by model {
                override fun acknowledgeScrollEffect(effect: HistoryScrollEffect) {
                    assertSame(model.pendingScrollEffect.value, effect)
                    assertEquals(model.historyItems.value.generation, effect.generation)
                    acknowledgments += effect
                    model.acknowledgeScrollEffect(effect)
                }
            }
            model.requestScrollToLatest()
            val scene = OffscreenScene.create(width = 480, height = 600) {
                Box(Modifier.height(300.dp)) {
                    AgentHistoryContent(displayed, rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() }))
                }
            }
            try {
                scene.settle("loaded committed body and acknowledged Latest") {
                    scene.hasText(text) && acknowledgments.size == 1 && model.pendingScrollEffect.value == null
                }
                assertSame(HistoryScrollTarget.Latest, acknowledgments.single().target)
                val body = scene.text(text)
                assertTrue(body.size.height > 300)
                assertEquals(300f, body.positionInRoot.y + body.size.height, 1f,
                    "Loading placeholder must not leave a long newest message at its header")
                assertTrue(model.followsLatest.value)
            } finally { scene.close() }
        }
    }

    test("product streaming text keeps its tail visible and respects genuine wheel departure and return") { historyGeometryModel(2) { model ->
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 128)
        val initialText = "Stream beginning"
        var tail = initialText
        var row = 0
        events.tryEmit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
            ResponseItemId("geometry-stream"), MessageRole.Assistant, listOf(ContentItem.OutputText(initialText)),
        )))
        val streaming = MutableStateFlow<HistoryStreamingItem?>(HistoryStreamingItem.Output(HistoryStreamingKind.Message, events))
        val displayed = object : AgentHistoryViewModel by model { override val streamingItem = streaming }
        var height by mutableIntStateOf(300)
        var scale by mutableFloatStateOf(1f)
        val state = ComposeHistoryViewState()
        val scene = OffscreenScene.create(width = 480, height = 800) {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.height(height.dp)) { AgentHistoryContent(displayed, rememberComposeHistoryListRenderer(state)) }
            }
        }
        fun renderedTail() = scene.text(tail).let { it.positionInRoot.y + it.size.height }
        suspend fun append(lines: Int) {
            val delta = (1..lines).joinToString("") { "\n\nStream row ${++row}" }
            tail = "Stream row $row"
            events.emit(ResponsesStreamEvent.OutputTextDelta("geometry-stream", 0, 0, delta))
            scene.settle("stream text applied") { scene.hasText(tail) }
        }
        try {
            scene.settle("initial stream") { scene.hasText(tail) }
            assertEquals(height.toFloat(), renderedTail(), 1f)
            assertTrue(scene.entryBounds(2).bottom <= scene.text("Assistant responding").boundsInWindow.top + 1f)
            repeat(3) {
                append(20)
                assertEquals(height.toFloat(), renderedTail(), 1f, "Streaming growth $it must keep the actual text tail visible")
            }
            scale = 1.5f
            scene.settle { scene.hasText(tail) }
            assertEquals(height.toFloat(), renderedTail(), 1f, "Font-only measurement change")
            for (viewport in listOf(180, 480, 240)) {
                height = viewport
                scene.settle { scene.hasText(tail) }
                assertEquals(height.toFloat(), renderedTail(), 1f, "Viewport-only measurement change")
            }
            scene.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 100f), scrollDelta = Offset(0f, -120f))
            scene.settle("wheel away within long newest item") { !model.followsLatest.value }
            append(10)
            assertTrue(renderedTail() > height + 20f, "New stream content must not steal the scrolled viewport")
            scene.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 100f), scrollDelta = Offset(0f, 5000f))
            scene.settle("genuine wheel return restores follow") { model.followsLatest.value }
            assertEquals(height.toFloat(), renderedTail(), 1f)
            append(10)
            assertEquals(height.toFloat(), renderedTail(), 1f, "Streaming follows again after wheel return")
            scene.frame()
        } finally { scene.close() }
    } }

    for (keyboard in listOf(false, true)) {
    test("expanding real newest tool details keeps the bottom edge without changing follow intent keyboard=$keyboard") { historyGeometryModel(2) { model ->
        val input = (1..100).joinToString("\n") { "Tool input line $it" }
        val pending = MutableStateFlow(listOf(PendingCustomToolEvent("geometry-tool", name = "geometry", input = input)))
        val displayed = object : AgentHistoryViewModel by model { override val pendingTools = pending }
        val scene = OffscreenScene.create(width = 480, height = 600) {
            Box(Modifier.height(300.dp)) {
                AgentHistoryContent(displayed, rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() }))
            }
        }
        try {
            scene.settle { scene.hasDescription("History tool: Running geometry") }
            if (keyboard) {
                val button = scene.nodes().single {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: Running geometry") == true
                }
                assertTrue(assertNotNull(button.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                scene.settle { scene.hasDescription("History tool: Running geometry") }
                scene.key(Key.Enter)
            }
            else scene.clickDescription("History tool: Running geometry")
            scene.settle { scene.hasText("Input") }
            if (keyboard) {
                val button = scene.nodes().single {
                    it.config.getOrNull(SemanticsActions.RequestFocus) != null &&
                        it.children.any { child ->
                            child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Input" } == true
                        }
                }
                assertTrue(assertNotNull(button.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                scene.settle { scene.hasText("Input") }
                scene.key(Key.Enter)
            }
            else scene.click("Input")
            scene.settle { scene.hasText("Input: $input") }
            val body = scene.text("Input: $input")
            assertEquals(300f, body.positionInRoot.y + body.size.height, 1f,
                "The actual expanded newest detail tail must be at the viewport bottom")
            assertTrue(model.followsLatest.value, "Disclosure is not genuine scrolling away")
            scene.frame()
        } finally { scene.close() }
    } }
    }

    test("measured newest tail remains bottom anchored through growth expansion font and viewport changes") { historyGeometryModel(0) { model ->
        var bodyHeight by mutableIntStateOf(40)
        var viewportHeight by mutableIntStateOf(300)
        var scale by mutableFloatStateOf(1f)
        var measuredTail = Float.NaN
        val state = ComposeHistoryViewState()
        val scene = OffscreenScene.create(width = 480, height = 800) {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.height(viewportHeight.dp)) {
                    val window by model.historyItems.collectAsState()
                    val follows by model.followsLatest.collectAsState()
                    val effect by model.pendingScrollEffect.collectAsState()
                    rememberComposeHistoryListRenderer(state).List(model, window, follows, effect, 0, "newest") { rows ->
                        rows.Row("newest", "geometry") {
                            Column {
                                Text("Newest heading")
                                Spacer(Modifier.height(bodyHeight.dp))
                                Text("Newest tail", Modifier.onGloballyPositioned {
                                    measuredTail = it.localToRoot(Offset(0f, it.size.height.toFloat())).y
                                })
                            }
                        }
                        rows.Row("older", "geometry") { Text("Older row") }
                    }
                }
            }
        }
        try {
            scene.settle { scene.hasText("Newest tail") && measuredTail.isFinite() }
            fun assertTail(label: String) {
                assertEquals(viewportHeight.toFloat(), measuredTail, 1f, "$label: tail must stay at latest edge")
                assertTrue(model.followsLatest.value)
            }
            assertTail("sparse")
            for (height in listOf(100, 600, 1200, 40)) {
                bodyHeight = height
                scene.settle { scene.hasText("Newest tail") }
                assertTail("growth/collapse=$height")
            }
            bodyHeight = 900
            scene.settle { scene.hasText("Newest tail") }
            scale = 1.5f
            scene.settle { scene.hasText("Newest tail") }
            assertTail("font")
            for (height in listOf(180, 480, 240)) {
                viewportHeight = height
                scene.settle { scene.hasText("Newest tail") }
                assertTail("viewport=$height")
            }
            scene.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 100f), scrollDelta = Offset(0f, -100f))
            scene.settle("real wheel leaves latest") { !model.followsLatest.value }
            val awayTail = measuredTail
            bodyHeight += 200
            scene.settle { !model.followsLatest.value }
            assertFalse(abs(measuredTail - viewportHeight) < 1f, "Growth must not steal a user-scrolled viewport")
            assertEquals(awayTail + 200f, measuredTail, 1f,
                "Keeping the reading position at the row's top lets appended height extend the tail")
            model.requestScrollToLatest()
            scene.settle("exact Latest acknowledgment and bottom geometry") {
                model.pendingScrollEffect.value == null && model.followsLatest.value &&
                    abs(measuredTail - viewportHeight) < 1f
            }
            bodyHeight += 100
            scene.settle { scene.hasText("Newest tail") }
            assertTail("growth after Latest")
        } finally { scene.close() }
    } }

    test("genuine keyboard page scroll owns follow intent just like a wheel scroll") { historyGeometryModel(0) { model ->
        val focus = FocusRequester()
        var tail = Float.NaN
        val scene = OffscreenScene.create(width = 480, height = 600) {
            Box(Modifier.height(300.dp)) {
                val window by model.historyItems.collectAsState()
                val follows by model.followsLatest.collectAsState()
                val effect by model.pendingScrollEffect.collectAsState()
                rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() })
                    .List(model, window, follows, effect, 0, "newest") { rows ->
                        rows.Row("newest", "geometry") {
                            Column {
                                Spacer(Modifier.height(1200.dp))
                                Text("Keyboard latest tail", Modifier.focusRequester(focus).focusable().onGloballyPositioned {
                                    tail = it.localToRoot(Offset(0f, it.size.height.toFloat())).y
                                })
                            }
                        }
                    }
            }
        }
        try {
            scene.settle { tail.isFinite() }
            assertTrue(focus.requestFocus())
            scene.settle { scene.hasText("Keyboard latest tail") }
            scene.key(Key.PageUp)
            scene.settle("PageUp must actually move the long newest row") { tail > 320f }
            assertEquals(450f, tail, 2f, "History pages by half its 300px viewport")
            assertFalse(model.followsLatest.value, "A genuine PageUp must stop automatic Latest following")
            scene.key(Key.PageDown)
            scene.settle("PageDown returns to the latest edge") { abs(tail - 300f) < 1f }
            assertTrue(model.followsLatest.value, "Genuine keyboard return must restore automatic following")
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.entryBounds(index: Int) = nodes().single {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History entry $index") == true
}.boundsInWindow

private suspend fun historyGeometryModel(
    count: Int, newestText: String? = null, block: suspend (AgentHistoryViewModel) -> Unit,
) = offscreenTest {
    val stored = InMemoryKodexAgentStorage.empty()
    for (index in 1..count) {
        stored.commit(
            StableUserMessage(listOf(ContentItem.InputText(
                if (index == count && newestText != null) newestText else "Geometry message $index",
            ))),
            timestamp = Instant.fromEpochMilliseconds(index * 1_000L),
        )
    }
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val model = createAgentHistoryViewModel(
        AgentHistorySource(stored, MutableStateFlow(count), MutableStateFlow(KodexAgentStateValue.UserMessage)),
        owner, MutableStateFlow(false),
    )
    try {
        withTimeout(5_000) { while (model.loadState.value != AgentHistoryLoadState.Ready) delay(1) }
        block(model)
    } finally {
        model.close()
        owner.coroutineContext.job.cancelAndJoin()
    }
}
