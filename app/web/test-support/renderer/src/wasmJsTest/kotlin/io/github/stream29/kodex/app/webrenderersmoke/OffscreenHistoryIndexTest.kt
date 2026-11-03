@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryIndexRenderer
import io.github.stream29.kodex.cli.agent.createHistoryIndexViewModel
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.*
import kotlin.time.Instant

val offscreenHistoryIndexTest by testSuite {
    test("Index menu renders typed plan without terminal markers or editable controls") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        val plan = StablePlanUpdate("plan", arguments = UpdatePlanArgs(
            "Explanation", listOf(PlanItemArg("Complete step", StepStatus.Completed)),
        ))
        model.detail = HistoryIndexReadState.Ready(HistoryIndexEntryDetail(
            HistoryIndexEntryKind.PlanUpdate, "[x] Complete step", plan = plan,
        ))
        var opening by mutableStateOf<HistoryIndexMenuOpening?>(null)
        val scene = OffscreenScene.create(width = 320, height = 240) {
            val renderer = rememberComposeHistoryIndexRenderer(onOpenMenu = { opening = it })
            Box(Modifier.width(320.dp).height(120.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
            val current = opening
            HistoryIndexMenuContent(current, renderer) { if (opening === current) opening = null }
        }
        try {
            scene.settle { scene.hasDescription("History index 1") }
            scene.secondaryIndex(1)
            scene.settle { scene.hasText("Complete step") && scene.hasText("Check out") }
            assertTrue(scene.hasText("Explanation"))
            assertFalse(scene.hasText("[x] Complete step"))
            val status = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.StateDescription) == "Completed"
            }
            assertNull(status.config.getOrNull(SemanticsActions.OnClick))
            scene.escape()
            scene.settle { opening == null && model.details.all { it.state.value == HistoryIndexReadState.Closed } }
        } finally { scene.close(); model.close() }
    } }

    test("linear Index decoration stays graphical while values and read states remain semantic") { offscreenTest {
        val model = IndexRenderFixture(listOf(1, 2, 3))
        val scene = OffscreenScene.create(width = 320, height = 240) {
            val renderer = rememberComposeHistoryIndexRenderer()
            Box(Modifier.width(320.dp).height(160.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
        }
        try {
            scene.settle { model.rows.size == 3 && scene.hasText("Row 1") && scene.hasText("Row 2") && scene.hasText("Row 3") }
            assertFalse(scene.indexTexts().any { it.startsWith("┌● Row") || it.startsWith("├● Row") || it.startsWith("└● Row") })
            model.rows.single { it.index == 1 }.state.value = HistoryIndexReadState.Loading
            model.rows.single { it.index == 2 }.state.value = HistoryIndexReadState.Failed
            model.rows.single { it.index == 3 }.state.value = HistoryIndexReadState.Ready(
                HistoryIndexEntry(3, HistoryIndexEntryKind.UserMessage, "┌● user text"),
            )
            scene.settle { scene.hasText("…") && scene.hasText("[error]") && scene.hasText("┌● user text") }
            fun rowState(index: Int) = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index $index") == true
            }.config.getOrNull(SemanticsProperties.StateDescription)
            assertEquals("Loading", rowState(1))
            assertEquals("Read failed", rowState(2))
            assertEquals("Read only", rowState(3))
            assertTrue(scene.nodes().none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { name -> name.contains("┌●") } == true
            })
            scene.frame()
        } finally { scene.close(); model.close() }
    } }

    test("Index row keyboard menu owns scrollable detail without a fake row click") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        val content = (0..100).joinToString("\n") { "complete detail line $it" }
        model.detail = HistoryIndexReadState.Ready(HistoryIndexEntryDetail(HistoryIndexEntryKind.UserMessage, content))
        var opening by mutableStateOf<HistoryIndexMenuOpening?>(null)
        val scene = OffscreenScene.create(width = 320, height = 240) {
            val renderer = rememberComposeHistoryIndexRenderer(onOpenMenu = { opening = it })
            Box(Modifier.width(320.dp).height(120.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
            val current = opening
            HistoryIndexMenuContent(current, renderer) { if (opening === current) opening = null }
        }
        try {
            scene.settle { scene.hasDescription("Open menu for history index 1") }
            assertFalse(scene.hasDescription("Actions for history index 1"))
            val row = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index 1") == true
            }
            assertNull(row.config.getOrNull(SemanticsActions.OnClick))
            assertTrue(row.boundsInRoot.height <= 32f)
            assertTrue(model.details.isEmpty())
            val unfocused = scene.frame()
            assertTrue(assertNotNull(row.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.settle { scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index 1") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertFalse(unfocused.contentEquals(scene.frame()), "Keyboard focus must be visible on the row.")
            scene.key(Key.F10, shift = true)
            scene.settle { scene.hasText(content) && scene.hasText("Check out") }
            assertEquals(1, model.details.size)
            assertEquals(1, model.timestamps.size)
            val scroll = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                .single { it.maxValue() > 0 }
            assertEquals(0f, scroll.value())
            scene.key(Key.PageDown)
            scene.settle { scroll.value() > 0 }
            assertTrue(scene.text("Check out").boundsInWindow.bottom <= 240)
            scene.key(Key.PageUp)
            scene.settle { scroll.value() == 0f }
            model.details.single().state.value = HistoryIndexReadState.Failed
            scene.settle { scene.hasText("Unable to read or decode the history entry.") }
            assertTrue(scene.hasText("Check out"), "A detail decode failure does not change valid scroll-only admission.")
            scene.escape()
            scene.settle { opening == null && model.details.single().state.value == HistoryIndexReadState.Closed &&
                scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index 1") == true &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                } }
            assertEquals(1, model.details.single().releaseCalls)
            assertEquals(1, model.timestamps.single().releaseCalls)
            assertTrue(model.scrolled.isEmpty())
            assertIs<HistoryIndexReadState.Ready<*>>(model.rows.single().state.value)
            scene.frame()
        } finally { scene.close(); model.close() }
    } }

    test("two product bodies use independent handles of the real Index owner") { offscreenTest {
        val stored = InMemoryKodexAgentStorage.empty()
        for (index in listOf(1, 4, 8)) stored.index[index] = StableUserMessage(listOf(ContentItem.InputText("Index row $index")))
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val model = createHistoryIndexViewModel(object : HistoryIndexDependencies {
            override val timeline = stored.index
            override val timestamp = stored.timestamp
            override val latestIndex = MutableStateFlow(8)
            override val cacheNonce = null
            override val externalWrite = MutableStateFlow(false)
            override fun requestScrollToStorageIndex(index: Int) = Unit
        }, owner)
        val handles = mutableListOf<HistoryIndexReadHandle<HistoryIndexEntry>>()
        val observed = object : HistoryIndexViewModel by model {
            override fun acquireRow(generation: Long, index: Int) =
                model.acquireRow(generation, index).also { handles += it }
        }
        var left by mutableStateOf(true)
        var output: OffscreenScene? = null
        try {
            withTimeout(5_000) { model.window.first { it.indexes == listOf(1, 4, 8) } }
            val scene = OffscreenScene.create {
                val renderer = rememberComposeHistoryIndexRenderer()
                Row(Modifier.fillMaxWidth().height(200.dp)) {
                    if (left) Box(Modifier.width(300.dp)) { HistoryIndexContent(observed, HistoryIndexSide.Left, renderer) }
                    Box(Modifier.width(300.dp)) { HistoryIndexContent(observed, HistoryIndexSide.Right, renderer) }
                }
            }.also { output = it }
            scene.settle { handles.size == 6 && handles.all { it.state.value is HistoryIndexReadState.Ready } &&
                scene.hasText("Index row 8") }
            assertTrue(scene.hasText("Index row 1"))
            assertTrue(scene.hasText("Index row 4"))
            left = false
            scene.settle { handles.count { it.state.value == HistoryIndexReadState.Closed } == 3 }
            assertEquals(3, handles.count { it.state.value is HistoryIndexReadState.Ready })
            assertTrue(model.isActive)
            scene.frame()
            scene.close()
            output = null
            assertTrue(handles.all { it.state.value == HistoryIndexReadState.Closed })
            assertTrue(model.isActive)
        } finally {
            output?.close()
            model.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }

    test("changing placement side acquires a fresh row lease instead of reusing the disposed handle") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        var side by mutableStateOf(HistoryIndexSide.Left)
        val scene = OffscreenScene.create {
            Box(Modifier.width(320.dp).height(160.dp)) {
                HistoryIndexContent(model, side, rememberComposeHistoryIndexRenderer())
            }
        }
        try {
            scene.settle { model.rows.size == 1 && scene.hasText("Row 1") }
            val first = model.rows.single()
            side = HistoryIndexSide.Right
            scene.settle { model.rows.size == 2 && first.state.value == HistoryIndexReadState.Closed && scene.hasText("Row 1") }
            assertNotSame(first, model.rows.last())
            assertIs<HistoryIndexReadState.Ready<*>>(model.rows.last().state.value)
            assertEquals(1, first.releaseCalls)
            assertTrue(model.isActive)
            scene.frame()
        } finally { scene.close(); model.close() }
    } }

    test("hover delays acquisition, redacts before drawing and releases only its owned detail") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        val question = StableRequestUserInputToolEvent(
            "secret", arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("secret", "Password", "Enter password", isSecret = true),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "secret" to RequestUserInputAnswer(listOf("SECRET")),
            ))),
        )
        model.detail = HistoryIndexReadState.Ready(HistoryIndexEntryDetail(
            HistoryIndexEntryKind.RequestUserInput, "SECRET raw fallback", question,
        ))
        var hovering by mutableStateOf<HistoryIndexInteraction?>(null)
        var opening by mutableStateOf<HistoryIndexMenuOpening?>(null)
        val scene = OffscreenScene.create {
            val renderer = rememberComposeHistoryIndexRenderer(
                onHoverChanged = { origin, entered ->
                    if (entered) hovering = origin else if (hovering === origin) hovering = null
                },
                onOpenMenu = { opening = it },
            )
            Box(Modifier.width(320.dp).height(160.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
            val current = hovering
            HistoryIndexHoverContent(current, renderer, {}, { if (hovering === current) hovering = null })
            val menu = opening
            HistoryIndexMenuContent(menu, renderer) { if (opening === menu) opening = null }
        }
        try {
            scene.settle { scene.hasDescription("History index 1") }
            val before = scene.frame()
            scene.scene.sendPointerEvent(PointerEventType.Move, scene.indexPosition(1))
            scene.advance()
            assertTrue(model.details.isEmpty(), "Detail acquisition must not run synchronously on enter.")
            scene.settle { scene.hasText("[hidden]") }
            assertEquals(1, model.details.size)
            assertFalse(scene.indexTexts().any { "SECRET" in it })
            assertTrue(scene.hasText("Request user input"))
            assertFalse(before.contentEquals(scene.frame()))
            model.details.single().state.value = HistoryIndexReadState.Failed
            scene.settle { scene.hasText("Unable to read or decode the history entry.") }
            hovering = null
            scene.settle { model.details.single().state.value == HistoryIndexReadState.Closed &&
                !scene.hasText("Unable to read or decode the history entry.") }
            assertEquals(1, model.details.single().releaseCalls)
            assertIs<HistoryIndexReadState.Ready<*>>(model.rows.single().state.value)
            assertTrue(model.isActive)
            val action = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open menu for history index 1") == true
            }
            assertTrue(assertNotNull(action.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            scene.settle { scene.hasText("Check out") && scene.hasText("[hidden]") }
            assertEquals(2, model.details.size)
            assertFalse(scene.indexTexts().any { "SECRET" in it })
            scene.escape()
            scene.settle { opening == null && model.details.all { it.state.value == HistoryIndexReadState.Closed } }
            assertTrue(model.details.all { it.releaseCalls == 1 })
        } finally { scene.close(); model.close() }
    } }

    val timestamps = listOf(
        HistoryIndexReadState.Loading, HistoryIndexReadState.Ready(null),
        HistoryIndexReadState.Ready(Instant.fromEpochMilliseconds(0)), HistoryIndexReadState.Failed, HistoryIndexReadState.Closed,
    )
    for ((branch, timestamp) in timestamps.withIndex()) {
        test("menu owns its exact optional timestamp and scroll-only Check out branch=$branch") { offscreenTest {
            val model = IndexRenderFixture(listOf(1))
            model.timestamp = timestamp
            var opening by mutableStateOf<HistoryIndexMenuOpening?>(null)
            val scene = OffscreenScene.create {
                val renderer = rememberComposeHistoryIndexRenderer(onOpenMenu = { opening = it })
                Box(Modifier.width(320.dp).height(160.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
                val current = opening
                HistoryIndexMenuContent(current, renderer, { if (opening === current) opening = null })
            }
            try {
                scene.settle { scene.hasDescription("History index 1") }
                scene.secondaryIndex(1)
                if (timestamp == HistoryIndexReadState.Closed) {
                    scene.settle { model.timestamps.size == 1 && opening == null }
                    assertFalse(scene.hasText("Check out"))
                    assertTrue(model.scrolled.isEmpty())
                } else {
                    scene.settle { scene.hasText("Check out") }
                    assertTrue(scene.hasText("Index: 1"))
                    assertEquals(branch == 2, scene.indexTexts().any { it.startsWith("Timestamp: ") })
                    assertEquals(1, model.timestamps.size)
                    if (branch == 0) {
                        model.timestamps.single().state.value = HistoryIndexReadState.Ready(Instant.fromEpochMilliseconds(0))
                        scene.settle { scene.indexTexts().any { it.startsWith("Timestamp: ") } }
                        assertEquals(1, model.timestamps.size, "Updating a timestamp must not acquire a second handle.")
                    }
                    val bounds = scene.text("Index: 1").boundsInWindow
                    assertTrue(bounds.left >= 0 && bounds.right <= 800 && bounds.top >= 0 && bounds.bottom <= 900)
                    scene.click("Check out")
                    scene.settle { opening == null && model.timestamps.single().state.value == HistoryIndexReadState.Closed }
                    assertEquals(listOf(7L to 1), model.scrolled)
                    assertEquals(1, model.timestamps.single().releaseCalls)
                }
                scene.frame()
            } finally { scene.close(); model.close() }
        } }
    }

    test("old menu callbacks cannot retarget a replacement revision or dismiss a fresh opening") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        var opening by mutableStateOf<HistoryIndexMenuOpening?>(null)
        var capturedCheckOut: (() -> Unit)? = null
        var capturedDismiss: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            val graphical = rememberComposeHistoryIndexRenderer(onOpenMenu = { opening = it })
            val renderer = object : HistoryIndexRenderer by graphical {
                @Composable override fun Menu(
                    opening: HistoryIndexMenuOpening, timestamp: Instant?, detail: HistoryIndexDetailPresentation?,
                    onDismiss: () -> Unit, onCheckOut: () -> Unit,
                ) {
                    capturedCheckOut = onCheckOut
                    capturedDismiss = onDismiss
                    graphical.Menu(opening, timestamp, detail, onDismiss, onCheckOut)
                }
            }
            Box(Modifier.width(320.dp).height(160.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
            val current = opening
            HistoryIndexMenuContent(current, renderer, { if (opening === current) opening = null })
        }
        try {
            scene.settle { scene.hasDescription("History index 1") }
            scene.secondaryIndex(1)
            scene.settle { scene.hasText("Check out") }
            val old = assertNotNull(opening)
            val checkOut = assertNotNull(capturedCheckOut)
            val dismiss = assertNotNull(capturedDismiss)
            model.window.value = model.window.value.copy(revision = 1)
            checkOut()
            assertTrue(model.scrolled.isEmpty(), "Revision admission must reject even before UI disposal runs.")
            scene.settle { opening == null && model.timestamps.single().state.value == HistoryIndexReadState.Closed }
            scene.secondaryIndex(1)
            scene.settle { model.timestamps.size == 2 && scene.hasText("Check out") }
            val fresh = assertNotNull(opening)
            assertNotSame(old, fresh)
            assertEquals(1L, fresh.interaction.target.revision)
            dismiss()
            assertSame(fresh, opening)
            checkOut()
            assertTrue(model.scrolled.isEmpty())
            scene.click("Check out")
            scene.settle { opening == null }
            assertEquals(listOf(7L to 1), model.scrolled)
        } finally { scene.close(); model.close() }
    } }

    test("large sparse Index stays lazy and follows append only after the user returns to its end") { offscreenTest {
        val model = IndexRenderFixture((1..1_000).toList())
        val scene = OffscreenScene.create {
            Box(Modifier.width(360.dp).height(240.dp)) {
                HistoryIndexContent(model, HistoryIndexSide.Right, rememberComposeHistoryIndexRenderer())
            }
        }
        try {
            scene.settle { scene.hasDescription("History index 1000") }
            assertTrue(model.rows.size < 50)
            assertTrue(model.rows.count { it.state.value != HistoryIndexReadState.Closed } < 20)
            val position = scene.indexPosition(1000)
            repeat(8) {
                scene.scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, -600f))
                scene.advance()
                delay(1)
            }
            scene.settle { model.rows.any { it.index < 900 && it.state.value != HistoryIndexReadState.Closed } }
            model.append(1_001)
            scene.settle { model.rows.any { it.index < 900 && it.state.value != HistoryIndexReadState.Closed } &&
                !scene.hasDescription("History index 1001") }
            assertFalse(scene.hasDescription("History index 1001"))
            scene.scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, 100_000f))
            scene.settle { scene.hasDescription("History index 1001") }
            model.append(1_002)
            scene.settle { scene.hasDescription("History index 1002") }
            assertTrue(model.rows.count { it.state.value != HistoryIndexReadState.Closed } < 20)
            scene.frame()
        } finally { scene.close(); model.close() }
    } }
}

internal class IndexRenderHandle<T>(
    override val generation: Long, override val index: Int, initial: HistoryIndexReadState<T>,
) : HistoryIndexReadHandle<T> {
    override val state = MutableStateFlow(initial)
    var releaseCalls = 0
    override fun release() { releaseCalls++; state.value = HistoryIndexReadState.Closed }
}

internal class IndexRenderFixture(indexes: List<Int>) : HistoryIndexViewModel {
    override val window = MutableStateFlow(HistoryIndexWindow(7, indexes))
    override var isActive = true
    val rows = mutableListOf<IndexRenderHandle<HistoryIndexEntry>>()
    val details = mutableListOf<IndexRenderHandle<HistoryIndexEntryDetail>>()
    val timestamps = mutableListOf<IndexRenderHandle<Instant?>>()
    var detail: HistoryIndexReadState<HistoryIndexEntryDetail> = HistoryIndexReadState.Ready(
        HistoryIndexEntryDetail(HistoryIndexEntryKind.UserMessage, "Detail"),
    )
    var timestamp: HistoryIndexReadState<Instant?> = HistoryIndexReadState.Ready(null)
    val scrolled = mutableListOf<Pair<Long, Int>>()
    override fun contains(generation: Long, index: Int): Boolean =
        isActive && generation == window.value.generation && index in window.value.indexes
    override suspend fun load(generation: Long, index: Int) = HistoryIndexEntry(index, HistoryIndexEntryKind.UserMessage, "Row $index")
    override suspend fun loadDetail(generation: Long, index: Int) =
        (detail as? HistoryIndexReadState.Ready)?.value ?: error("Fixture detail is not ready")
    override suspend fun readMessageTimestamp(generation: Long, index: Int): Instant? =
        (timestamp as? HistoryIndexReadState.Ready)?.value
    override fun acquireRow(generation: Long, index: Int) = IndexRenderHandle<HistoryIndexEntry>(
        generation, index, if (contains(generation, index)) HistoryIndexReadState.Ready(
            HistoryIndexEntry(index, HistoryIndexEntryKind.UserMessage, "Row $index"),
        ) else HistoryIndexReadState.Closed,
    ).also { rows += it }
    override fun acquireDetail(generation: Long, index: Int) = IndexRenderHandle(
        generation, index, if (contains(generation, index)) detail else HistoryIndexReadState.Closed,
    ).also { details += it }
    override fun acquireTimestamp(generation: Long, index: Int) = IndexRenderHandle(
        generation, index, if (contains(generation, index)) timestamp else HistoryIndexReadState.Closed,
    ).also { timestamps += it }
    override fun checkOut(generation: Long, index: Int): Boolean {
        if (!contains(generation, index)) return false
        scrolled += generation to index
        return true
    }
    override fun close() {
        isActive = false
        rows.forEach { it.release() }
        details.forEach { it.release() }
        timestamps.forEach { it.release() }
    }
    fun append(index: Int) { window.value = window.value.copy(indexes = window.value.indexes + index) }
}

private fun OffscreenScene.indexPosition(index: Int): Offset = nodes().first {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index $index") == true
}.boundsInWindow.center

private fun OffscreenScene.secondaryIndex(index: Int) {
    val position = indexPosition(index)
    scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
    scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
}

private fun OffscreenScene.indexTexts(): List<String> = nodes().flatMap { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
