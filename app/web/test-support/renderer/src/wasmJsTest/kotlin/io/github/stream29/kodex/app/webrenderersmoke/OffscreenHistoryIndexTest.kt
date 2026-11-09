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
    test("disposed Index anchor rejects retained menu requests while its target remains current") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        var show by mutableStateOf(true)
        var row: HistoryIndexRowInteraction? = null
        val openings = mutableListOf<HistoryIndexMenuOpening>()
        val scene = OffscreenScene.create(width = 320, height = 120) {
            val graphical = rememberComposeHistoryIndexRenderer(onOpenMenu = openings::add)
            val renderer = object : HistoryIndexRenderer by graphical {
                @Composable override fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction =
                    graphical.rememberInteraction(target, side).also { row = it }
            }
            if (show) HistoryIndexContent(model, HistoryIndexSide.Left, renderer)
        }
        try {
            scene.settle { scene.hasDescription("History index 1") }
            scene.key(Key.Tab)
            scene.key(Key.F10, shift = true)
            scene.settle { openings.size == 1 }
            show = false
            scene.settle { !scene.hasDescription("History index 1") }
            assertTrue(assertNotNull(row).target.isCurrent())
            assertFalse(assertNotNull(row).isPlaced)
            assertNotNull(row).emitMenu()
            assertEquals(1, openings.size)
            assertTrue(model.scrolled.isEmpty())
        } finally { scene.close(); model.close() }
    } }

    for (key in listOf(Key.PageUp, Key.DirectionUp)) {
        test("Index keyboard scrolling beyond bounds retains reading position on append key=$key") { offscreenTest {
            val model = IndexRenderFixture((1..100).toList())
            val scene = OffscreenScene.create(width = 360, height = 240) {
                HistoryIndexContent(model, HistoryIndexSide.Right, rememberComposeHistoryIndexRenderer())
            }
            try {
                scene.settle { scene.hasDescription("History index 100") }
                scene.key(Key.Tab)
                scene.settle { scene.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true } }
                repeat(12) { scene.key(key); scene.advance(); delay(1) }
                scene.settle { !scene.hasDescription("History index 100") }
                model.append(101)
                repeat(5) { scene.advance(); delay(1) }
                assertFalse(scene.hasDescription("History index 101"), "Append must not reclaim a keyboard-scrolled viewport")
            } finally { scene.close(); model.close() }
        } }
    }

    test("Index menu renders typed plan without terminal markers or editable controls") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        val plan = StablePlanUpdate("plan", arguments = UpdatePlanArgs(
            "Explanation", listOf(PlanItemArg("Complete step", StepStatus.Completed)),
        ))
        model.setDetail(1, HistoryIndexEntryDetail(
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
            scene.settle { opening == null }
        } finally { scene.close(); model.close() }
    } }

    test("linear Index decoration stays graphical while values remain semantic") { offscreenTest {
        val model = IndexRenderFixture(listOf(1, 2, 3))
        model.setSummary(3, "┌● user text")
        val scene = OffscreenScene.create(width = 320, height = 240) {
            val renderer = rememberComposeHistoryIndexRenderer()
            Box(Modifier.width(320.dp).height(160.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
        }
        try {
            scene.settle { scene.hasText("Row 1") && scene.hasText("Row 2") && scene.hasText("┌● user text") }
            assertFalse(scene.indexTexts().any { it.startsWith("┌● Row") || it.startsWith("├● Row") || it.startsWith("└● Row") })
            fun rowState(index: Int) = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index $index") == true
            }.config.getOrNull(SemanticsProperties.StateDescription)
            assertEquals("Read only", rowState(1))
            assertEquals("Read only", rowState(2))
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
        model.setDetail(1, HistoryIndexEntryDetail(HistoryIndexEntryKind.UserMessage, content))
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
            val unfocused = scene.frame()
            assertTrue(assertNotNull(row.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.settle { scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index 1") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertFalse(unfocused.contentEquals(scene.frame()), "Keyboard focus must be visible on the row.")
            scene.key(Key.F10, shift = true)
            scene.settle { scene.hasText(content) && scene.hasText("Check out") }
            val scroll = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                .single { it.maxValue() > 0 }
            assertEquals(0f, scroll.value())
            scene.key(Key.PageDown)
            scene.settle { scroll.value() > 0 }
            assertTrue(scene.text("Check out").boundsInWindow.bottom <= 240)
            scene.key(Key.PageUp)
            scene.settle { scroll.value() == 0f }
            scene.escape()
            scene.settle { opening == null &&
                scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index 1") == true &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                } }
            assertTrue(model.scrolled.isEmpty())
            scene.frame()
        } finally { scene.close(); model.close() }
    } }

    test("two product bodies share the real Index owner") { offscreenTest {
        val stored = InMemoryKodexAgentStorage.empty()
        for (index in 1..8) {
            if (index in setOf(1, 4, 8)) {
                stored.commit(StableUserMessage(listOf(ContentItem.InputText("Index row $index"))))
            } else {
                stored.commit(UnstableSnapshot(emptyList()), timestamp = Instant.DISTANT_PAST)
            }
        }
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val model = createHistoryIndexViewModel(object : HistoryIndexDependencies {
            override val storage = stored
            override val latestIndex = MutableStateFlow(8)
            override val cacheNonce = null
            override val externalWrite = MutableStateFlow(false)
            override fun requestScrollToStorageIndex(index: Int) = Unit
        }, owner)
        var left by mutableStateOf(true)
        var output: OffscreenScene? = null
        try {
            withTimeout(5_000) { model.window.first { it.indexes == listOf(1, 4, 8) } }
            val scene = OffscreenScene.create {
                val renderer = rememberComposeHistoryIndexRenderer()
                Row(Modifier.fillMaxWidth().height(200.dp)) {
                    if (left) Box(Modifier.width(300.dp)) { HistoryIndexContent(model, HistoryIndexSide.Left, renderer) }
                    Box(Modifier.width(300.dp)) { HistoryIndexContent(model, HistoryIndexSide.Right, renderer) }
                }
            }.also { output = it }
            scene.settle { scene.hasText("Index row 8") }
            assertTrue(scene.hasText("Index row 1"))
            assertTrue(scene.hasText("Index row 4"))
            left = false
            scene.settle { scene.hasText("Index row 8") }
            assertTrue(model.isActive)
            scene.frame()
            scene.close()
            output = null
            assertTrue(model.isActive)
        } finally {
            output?.close()
            model.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }

    test("changing placement side keeps row visible") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        var side by mutableStateOf(HistoryIndexSide.Left)
        val scene = OffscreenScene.create {
            Box(Modifier.width(320.dp).height(160.dp)) {
                HistoryIndexContent(model, side, rememberComposeHistoryIndexRenderer())
            }
        }
        try {
            scene.settle { scene.hasText("Row 1") }
            side = HistoryIndexSide.Right
            scene.settle { scene.hasText("Row 1") }
            assertTrue(model.isActive)
            scene.frame()
        } finally { scene.close(); model.close() }
    } }

    test("hover delays display, redacts before drawing and dismisses cleanly") { offscreenTest {
        val model = IndexRenderFixture(listOf(1))
        val question = StableRequestUserInputToolEvent(
            "secret", arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("secret", "Password", "Enter password", isSecret = true),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "secret" to RequestUserInputAnswer(listOf("SECRET")),
            ))),
        )
        model.setDetail(1, HistoryIndexEntryDetail(
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
            assertFalse(scene.hasText("[hidden]"), "Hover popup must delay before appearing.")
            scene.settle { scene.hasText("[hidden]") }
            assertFalse(scene.indexTexts().any { "SECRET" in it })
            assertTrue(scene.hasText("Request user input"))
            assertFalse(before.contentEquals(scene.frame()))
            hovering = null
            scene.settle { !scene.hasText("Request user input") }
            assertTrue(model.isActive)
            val action = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open menu for history index 1") == true
            }
            assertTrue(assertNotNull(action.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            scene.settle { scene.hasText("Check out") && scene.hasText("[hidden]") }
            assertFalse(scene.indexTexts().any { "SECRET" in it })
            scene.escape()
            scene.settle { opening == null }
        } finally { scene.close(); model.close() }
    } }

    for ((branch, timestamp) in listOf(null, Instant.fromEpochMilliseconds(0)).withIndex()) {
        test("menu renders optional timestamp and scroll-only Check out branch=$branch") { offscreenTest {
            val model = IndexRenderFixture(listOf(1))
            model.setTimestamp(1, timestamp)
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
                scene.settle { scene.hasText("Check out") }
                assertTrue(scene.hasText("Index: 1"))
                assertEquals(timestamp != null, scene.indexTexts().any { it.startsWith("Timestamp: ") })
                val bounds = scene.text("Index: 1").boundsInWindow
                assertTrue(bounds.left >= 0 && bounds.right <= 800 && bounds.top >= 0 && bounds.bottom <= 900)
                scene.click("Check out")
                scene.settle { opening == null }
                assertEquals(listOf(7L to 1), model.scrolled)
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
            scene.settle { opening == null }
            scene.secondaryIndex(1)
            scene.settle { scene.hasText("Check out") }
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
            assertTrue(model.visibleRows.size < 50)
            val position = scene.indexPosition(1000)
            repeat(8) {
                scene.scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, -600f))
                scene.advance()
                delay(1)
            }
            scene.settle { model.visibleRows.any { it < 900 } }
            model.append(1_001)
            scene.settle { model.visibleRows.any { it < 900 } && !scene.hasDescription("History index 1001") }
            assertFalse(scene.hasDescription("History index 1001"))
            scene.scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, 100_000f))
            scene.settle { scene.hasDescription("History index 1001") }
            model.append(1_002)
            scene.settle { scene.hasDescription("History index 1002") }
            scene.frame()
        } finally { scene.close(); model.close() }
    } }
}

internal class IndexRenderFixture(indexes: List<Int>) : HistoryIndexViewModel {
    override val window = MutableStateFlow(
        HistoryIndexWindow(
            7,
            indexes.map { idx ->
                HistoryIndexEntry(
                    idx,
                    HistoryIndexEntryKind.UserMessage,
                    "Row $idx",
                    HistoryIndexEntryDetail(HistoryIndexEntryKind.UserMessage, "Detail"),
                )
            },
        ),
    )
    override var isActive = true
    val visibleRows = mutableListOf<Int>()
    val scrolled = mutableListOf<Pair<Long, Int>>()
    override fun contains(generation: Long, index: Int): Boolean =
        isActive && generation == window.value.generation && index in window.value.indexes
    override fun onRowVisible(generation: Long, index: Int) {
        visibleRows += index
    }
    override fun checkOut(generation: Long, index: Int): Boolean {
        if (!contains(generation, index)) return false
        scrolled += generation to index
        return true
    }
    override fun close() {
        isActive = false
    }
    fun append(index: Int) {
        val next = HistoryIndexEntry(
            index,
            HistoryIndexEntryKind.UserMessage,
            "Row $index",
            HistoryIndexEntryDetail(HistoryIndexEntryKind.UserMessage, "Detail"),
        )
        window.value = window.value.copy(entries = window.value.entries + next)
    }
    fun setDetail(index: Int, detail: HistoryIndexEntryDetail) {
        window.value = window.value.copy(
            entries = window.value.entries.map { if (it.index == index) it.copy(kind = detail.kind, detail = detail) else it },
        )
    }
    fun setSummary(index: Int, summary: String) {
        window.value = window.value.copy(
            entries = window.value.entries.map { if (it.index == index) it.copy(summary = summary) else it },
        )
    }
    fun setTimestamp(index: Int, timestamp: Instant?) {
        window.value = window.value.copy(
            entries = window.value.entries.map { if (it.index == index) it.copy(messageTimestamp = timestamp) else it },
        )
    }
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
