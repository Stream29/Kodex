package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryScrollEffect
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val agentHistoryRendererStateTest by testSuite {
    test("genuine pointer and keyboard input owns follow intent, not programmatic positioning") {
        val model = RendererHistoryModel(rendererWindow())
        val viewState = AgentHistoryViewState()
        val interactions = mutableListOf<ScrollInteraction>()
        runMosaicTest {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                viewState.scrollInteractionSource.interactions.collect { interactions += it }
            }
            try {
                val initial = setContentAndSnapshot {
                    Column(Modifier.width(40).height(8)) {
                        AgentHistoryView(model, RendererShellSessions, viewState)
                    }
                }
                settleRendererFrames()
                assertTrue("row-30" in initial)
                sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
                settleRendererFrames()
                assertTrue(model.followsLatest.value, "A zero-consumption attempt cannot exit follow.")

                repeat(2) {
                    sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
                }
                val older = awaitSnapshot()
                settleRendererFrames()
                assertFalse(model.followsLatest.value)
                assertFalse("row-30" in older, "Pointer input must really scroll away from newest.")
                assertTrue(interactions.any { it.source == ScrollInputSource.Pointer && it.consumedDelta < 0 })

                // Widget-only positioning must not re-enable follow at newest.
                viewState.listState.requestScrollToStart()
                val programmaticNewest = awaitSnapshot()
                settleRendererFrames()
                assertTrue("row-30" in programmaticNewest)
                assertFalse(model.followsLatest.value)

                sendKeyEvent(KeyboardEvent(9)) // Real focus traversal into a stored entry.
                settleRendererFrames()
                sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
                val keyboardOlder = awaitSnapshot()
                settleRendererFrames()
                assertFalse(model.followsLatest.value)
                assertFalse("row-30" in keyboardOlder)
                assertTrue(interactions.any { it.source == ScrollInputSource.Keyboard && it.consumedDelta < 0 })

                var latestOutput = keyboardOlder
                repeat(40) {
                    if (viewState.listState.canScrollForward) {
                        sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
                        latestOutput = awaitSnapshot()
                        settleRendererFrames()
                    }
                }
                assertFalse(viewState.listState.canScrollForward)
                assertTrue("row-30" in latestOutput)
                assertTrue(model.followsLatest.value, "Only genuine return to newest restores follow.")
                assertTrue(model.visibleItems.isNotEmpty(), "Visible children must be reported by real layout.")
            } finally {
                collector.cancel()
                collector.join()
            }
        }
    }

    test("real focus relocation does not become a user scroll intent") {
        val model = RendererHistoryModel(rendererWindow())
        val viewState = AgentHistoryViewState()
        val interactions = mutableListOf<ScrollInteraction>()
        runMosaicTest {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                viewState.scrollInteractionSource.interactions.collect { interactions += it }
            }
            try {
                setContentAndSnapshot {
                    Column(Modifier.width(40).height(5)) {
                        AgentHistoryView(model, RendererShellSessions, viewState)
                    }
                }
                sendKeyEvent(KeyboardEvent(9))
                // Shift+Tab walks real rows, including a beyond-bounds row. It is keyboard
                // input, but its resulting scroll source is FocusRelocation, not Keyboard.
                repeat(12) {
                    sendKeyEvent(KeyboardEvent(9, modifiers = ModifierShift))
                    settleRendererFrames()
                }
                assertTrue(interactions.any { it.source == ScrollInputSource.FocusRelocation })
                assertTrue(model.followsLatest.value, "Focus relocation must not exit follow intent.")
                val rows = viewState.listState.layoutInfo.visibleItemsInfo
                assertTrue(rows.any { it.key is MessageHistoryItemViewModel })
                assertTrue(rows.any { (it.key as? MessageHistoryItemViewModel)?.index == 30 })
            } finally {
                collector.cancel()
                collector.join()
            }
        }
    }

    test("real VM navigation accepted before mount renders and acknowledges its exact target") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..30) storage.index[index] =
                    StableUserMessage(listOf(ContentItem.InputText("row-$index")))
            }
            val model = createAgentHistoryViewModel(
                AgentHistorySource(runtime.storage, runtime.latestIndex, runtime.state),
                supervisorChildScope(), MutableStateFlow(false),
            )
            val viewState = AgentHistoryViewState()
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.loadState.first { it == AgentHistoryLoadState.Ready }
                        model.requestScrollToStorageIndex(3)
                        model.pendingScrollEffect.first { it != null }
                    }
                }
                val effect = model.pendingScrollEffect.value!!
                val target = (effect.target as HistoryScrollTarget.Item).item
                assertSame(effect, model.pendingScrollEffect.value)
                runMosaicTest {
                    var output = setContentAndSnapshot {
                        Column(Modifier.width(40).height(8)) {
                            AgentHistoryView(model, RendererShellSessions, viewState)
                        }
                    }
                    repeat(20) {
                        settleRendererFrames()?.let { output = it }
                    }
                    assertNull(model.pendingScrollEffect.value)
                    assertTrue("row-3" in output, "Late mount must show the accepted storage target:\n$output")
                    assertTrue(viewState.listState.layoutInfo.visibleItemsInfo.any { it.key === target })
                }
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("repeated targets resolve current transient prefix instead of a VM row index") {
        val window = rendererWindow(hasNewer = true)
        val model = RendererHistoryModel(window)
        val viewState = AgentHistoryViewState()
        model.streamingItem.value = HistoryStreamingItem.Started
        model.pendingTools.value = listOf(
            PendingCustomToolEvent(callId = "a", name = "alpha", input = "a"),
            PendingCustomToolEvent(callId = "b", name = "beta", input = "b"),
        )
        model.requestScrollToStorageIndex(15)
        val first = model.pendingScrollEffect.value!!
        runMosaicTest {
            setContentAndSnapshot {
                Column(Modifier.width(40).height(8)) {
                    AgentHistoryView(model, RendererShellSessions, viewState)
                }
            }
            repeat(5) { settleRendererFrames() }
            assertSame(first, model.acknowledgedEffects.single())
            val target = (first.target as HistoryScrollTarget.Item).item
            var measured = viewState.listState.layoutInfo.visibleItemsInfo.single { it.key === target }
            assertEquals(4 + 15, measured.index, "stream + two pending + newer marker")

            model.streamingItem.value = null
            model.pendingTools.value = emptyList()
            model.requestScrollToStorageIndex(15)
            val second = model.pendingScrollEffect.value!!
            assertNotSame(first, second)
            val output = awaitSnapshot()
            repeat(5) { settleRendererFrames() }
            assertTrue("row-15" in output)
            assertSame(second, model.acknowledgedEffects.last())
            measured = viewState.listState.layoutInfo.visibleItemsInfo.single { it.key === target }
            assertEquals(1 + 15, measured.index, "Only the actual newer marker remains.")
            assertNull(model.pendingScrollEffect.value)
        }
    }

    test("exact renderer state survives tab unmount without mirroring a business window") {
        val model = RendererHistoryModel(rendererWindow())
        model.followsLatest.value = false
        val viewState = AgentHistoryViewState()
        var mounted by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                Column(Modifier.width(40).height(9)) {
                    Column(Modifier.height(8)) {
                        if (mounted) AgentHistoryView(model, RendererShellSessions, viewState)
                        else Text("other tab")
                    }
                    // The real host has a tab/composer focus owner outside History. Without
                    // one, remount activates the first partially visible History row and
                    // legitimate FocusRelocation brings that row fully into view.
                    Text("host focus", Modifier.focusable(autoFocus = true))
                }
            }
            sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            val firstScrolledOutput = awaitSnapshot()
            val before = settleRendererFrames() ?: firstScrolledOutput
            val anchor = viewState.listState.layoutInfo.visibleItemsInfo.first()
            val anchorIndex = viewState.listState.firstVisibleItemIndex
            val anchorOffset = viewState.listState.firstVisibleItemScrollOffset
            mounted = false
            assertTrue("other tab" in awaitSnapshot())
            assertTrue(model.visibleItems.isEmpty(), "Unmount clears portable viewport retention.")
            mounted = true
            val firstRemountOutput = awaitSnapshot()
            val after = settleRendererFrames() ?: firstRemountOutput
            assertEquals(before, after)
            val restored = viewState.listState.layoutInfo.visibleItemsInfo.first()
            assertSame(anchor.key, restored.key)
            assertEquals(anchor.offset, restored.offset)
            assertEquals(anchorIndex, viewState.listState.firstVisibleItemIndex)
            assertEquals(anchorOffset, viewState.listState.firstVisibleItemScrollOffset)
        }
    }

    test("expanded Work Group reports its actual top-level row and renders children from real input") {
        val group = RendererWorkGroup()
        val model = RendererHistoryModel(RendererWindow(listOf(group)))
        val viewState = AgentHistoryViewState()
        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Column(Modifier.width(40).height(8)) {
                    AgentHistoryView(model, RendererShellSessions, viewState)
                }
            }
            settleRendererFrames()
            assertTrue("> Take 2 actions" in collapsed)
            val row = viewState.listState.layoutInfo.visibleItemsInfo.single { it.key === group }
            sendMouseEvent(MouseEvent(1, row.offset, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(1, row.offset, MouseEvent.Type.Release))
            val firstReleaseOutput = awaitSnapshot()
            val expanded = settleRendererFrames() ?: firstReleaseOutput
            assertTrue(group.state.value is WorkGroupHistoryItemState.Expanded,
                "Real pointer release must expand the group at ${row.offset}:\n$expanded")
            assertTrue("v Take 2 actions" in expanded, "Expanded header must render:\n$expanded")
            assertTrue("Think" in expanded, "Expanded children must render:\n$expanded")
            assertEquals(listOf(group), model.visibleItems)
            assertEquals(1, model.historyItems.value.size)
            assertEquals(2, (group.state.value as WorkGroupHistoryItemState.Expanded).children.size)
        }
    }
}

private suspend fun TestMosaic<String>.settleRendererFrames(): String? {
    var lastOutput: String? = null
    repeat(4) {
        try {
            lastOutput = awaitSnapshot(100.milliseconds)
        } catch (_: TimeoutCancellationException) {
            // Frames still execute effects when geometry/output does not need another redraw.
        }
    }
    return lastOutput
}

private fun rendererWindow(hasNewer: Boolean = false): RendererWindow =
    RendererWindow((30 downTo 1).map { RendererMessage(it) }, hasNewer)

private class RendererWindow(
    val items: List<HistoryItemViewModel>,
    override val hasNewer: Boolean = false,
) : HistoryItemWindow {
    override val generation = 0L
    override val size: Int get() = items.size
    override val hasOlder = false
    override fun peek(index: Int): HistoryItemViewModel = items[index]
    override fun get(index: Int): HistoryItemViewModel = items[index]
    override fun requestOlder() = Unit
    override fun requestNewer() = Unit
}

private class RendererMessage(override val index: Int) : MessageHistoryItemViewModel {
    override val state = MutableStateFlow<MessageHistoryItemState>(
        MessageHistoryItemState.Ready(
            StableUserMessage(listOf(ContentItem.InputText("row-$index"))), Duration.ZERO,
        ),
    )
    override suspend fun readTimestamp(): Instant? = null
}

private class RendererWorkGroup : WorkGroupHistoryItemViewModel {
    override val indexRange = 2..3
    override val itemCount = 2
    override val state = MutableStateFlow<WorkGroupHistoryItemState>(
        WorkGroupHistoryItemState.Collapsed(Duration.ZERO),
    )
    override fun expand() {
        state.value = WorkGroupHistoryItemState.Expanded(
            listOf(ReasoningHistoryItemViewModel(3, Duration.ZERO),
                ReasoningHistoryItemViewModel(2, Duration.ZERO)),
            Duration.ZERO,
        )
    }
    override fun collapse() {
        state.value = WorkGroupHistoryItemState.Collapsed(Duration.ZERO)
    }
}

private class RendererHistoryModel(window: RendererWindow) : AgentHistoryViewModel {
    override val historyItems = MutableStateFlow<HistoryItemWindow>(window)
    override val loadState = MutableStateFlow<AgentHistoryLoadState>(AgentHistoryLoadState.Ready)
    override val pendingTools = MutableStateFlow<List<UnstableCleanEvent>>(emptyList())
    override val streamingItem = MutableStateFlow<HistoryStreamingItem?>(null)
    override val activeTurnDuration = MutableStateFlow<Duration?>(null)
    override val followsLatest = MutableStateFlow(true)
    override val pendingScrollEffect = MutableStateFlow<HistoryScrollEffect?>(null)
    var visibleItems: List<HistoryItemViewModel> = emptyList()
        private set
    val acknowledgedEffects = mutableListOf<HistoryScrollEffect>()

    override fun reportViewport(window: HistoryItemWindow, visibleItems: List<HistoryItemViewModel>) {
        if (historyItems.value === window) this.visibleItems = visibleItems.toList()
    }
    override fun setFollowsLatest(window: HistoryItemWindow, followsLatest: Boolean) {
        if (historyItems.value === window && (!followsLatest || !window.hasNewer)) {
            this.followsLatest.value = followsLatest
        }
    }
    override fun acknowledgeScrollEffect(effect: HistoryScrollEffect) {
        if (pendingScrollEffect.compareAndSet(effect, null)) acknowledgedEffects += effect
    }
    override fun contains(generation: Long, storageIndex: Int): Boolean =
        generation == historyItems.value.generation &&
            (historyItems.value as RendererWindow).items.any {
                (it as? MessageHistoryItemViewModel)?.index == storageIndex
            }
    override fun requestScrollToLatest() {
        followsLatest.value = true
        pendingScrollEffect.value = HistoryScrollEffect(
            historyItems.value.generation, HistoryScrollTarget.Latest,
        )
    }
    override fun requestScrollToStorageIndex(storageIndex: Int) {
        val window = historyItems.value as RendererWindow
        val target = window.items.first { (it as? MessageHistoryItemViewModel)?.index == storageIndex }
        followsLatest.value = false
        pendingScrollEffect.value = HistoryScrollEffect(window.generation, HistoryScrollTarget.Item(target))
    }
    override fun close() {
        pendingScrollEffect.value = null
        visibleItems = emptyList()
    }
}

private object RendererShellSessions : AgentShellSessionRegistry {
    override val activeSessions = MutableStateFlow<Map<Int, AgentShellSession>>(emptyMap())
}
