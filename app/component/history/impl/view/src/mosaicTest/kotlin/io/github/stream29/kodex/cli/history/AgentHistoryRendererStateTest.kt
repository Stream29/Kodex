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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    for (newer in listOf(false, true)) {
        val direction = if (newer) "newer" else "older"
        test("delayed $direction payload has a real blank row when logical edge is consumed") {
            coroutineScope {
                val releasePayload = CompletableDeferred<Unit>()
                lateinit var edge: RendererMessage
                val payload = launch(start = CoroutineStart.LAZY) {
                    releasePayload.await()
                    edge.state.value = rendererMessagePayload(edge.index)
                }
                edge = RendererMessage(if (newer) 30 else 1, payload)
                var demands = 0
                val window = RendererWindow(
                    (30 downTo 1).map { if (it == edge.index) edge else RendererMessage(it) },
                    hasNewer = true, hasOlder = true,
                    onOlderDemand = { if (!newer) demands++ },
                    onNewerDemand = { if (newer) demands++ },
                )
                val model = RendererHistoryModel(window)
                model.followsLatest.value = false
                val viewState = AgentHistoryViewState()
                try {
                    runMosaicTest {
                        try {
                            setContentAndSnapshot {
                                Column(Modifier.width(40).height(8)) {
                                    AgentHistoryView(model, RendererShellSessions, viewState)
                                }
                            }
                            settleRendererFrames()
                            if (newer) viewState.listState.requestScrollToStart()
                            else viewState.listState.requestScrollToEnd()
                            settleRendererFrames()
                            assertTrue(payload.isActive && !payload.isCompleted)
                            assertTrue(edge in window.accessedItems, "Only renderer get may activate the lazy payload.")
                            assertTrue(edge.state.value is MessageHistoryItemState.Loading)
                            val blankLayout = viewState.listState.layoutInfo
                            val blank = blankLayout.visibleItemsInfo.single { it.key === edge }
                            // Mosaic StringTextLayout splits "" into ONE line; it is not a
                            // zero-height message. The empty demand marker is a different item.
                            assertEquals(1, blank.size, "The actual Loading renderer must be measured.")
                            assertEquals(1, demands, "Visibility, not payload Ready, admits one-shot demand.")
                            assertTrue(window.isMeasuredHistoryWindow(
                                model, blankLayout, viewState.listState.canScrollBackward,
                            ), "Count/key identity alone can accept a blank payload frame.")
                            assertFalse(window.isMeasuredHistoryWindow(
                                model, blankLayout, viewState.listState.canScrollBackward,
                                requireReadyRows = true,
                            ), "Every pressure handoff must reject unloaded visible rows.")
                            assertFalse(window.isMeasuredHistoryWindow(
                                model, blankLayout, viewState.listState.canScrollBackward,
                                inputTarget = edge, expectedTargetHeight = 2,
                            ))
                            val anchor = blankLayout.visibleItemsInfo.first()

                            releasePayload.complete(Unit)
                            payload.join() // No renderer frame is pumped by the payload completion.
                            assertTrue(edge.state.value is MessageHistoryItemState.Ready)
                            assertSame(blankLayout, viewState.listState.layoutInfo)
                            assertFalse(window.isMeasuredHistoryWindow(
                                model, blankLayout, viewState.listState.canScrollBackward,
                                inputTarget = edge, expectedTargetHeight = 2,
                            ), "Ready cannot borrow the previously measured Loading height.")
                            assertFalse(window.isMeasuredHistoryWindow(
                                model, blankLayout, viewState.listState.canScrollBackward,
                                requireReadyRows = true,
                            ), "Visible Ready alone must not authorize a still-blank measured frame.")

                            settleRendererFrames()
                            val readyLayout = viewState.listState.layoutInfo
                            assertTrue(window.isMeasuredHistoryWindow(
                                model, readyLayout, viewState.listState.canScrollBackward,
                                inputTarget = edge, expectedTargetHeight = 2,
                            ))
                            assertTrue(window.isMeasuredHistoryWindow(
                                model, readyLayout, viewState.listState.canScrollBackward,
                                requireReadyRows = true,
                            ))
                            val restored = readyLayout.visibleItemsInfo.first()
                            assertSame(anchor.key, restored.key)
                            assertEquals(anchor.offset, restored.offset)
                            assertEquals(1, demands, "Payload height alone does not restart the one-shot effect.")
                            assertFalse(model.followsLatest.value, "Logical positioning is not genuine input.")
                        } finally {
                            cancel()
                        }
                    }
                } finally {
                    releasePayload.cancel()
                    payload.cancel()
                    withContext(NonCancellable) { payload.join() }
                    model.close()
                }
            }
        }

        for (keyboard in listOf(false, true)) {
            val input = if (keyboard) "keyboard" else "pointer"
            test("delayed $direction payload preserves the consumed edge anchor until real $input input") {
                coroutineScope {
                    val releasePayload = CompletableDeferred<Unit>()
                    lateinit var edge: RendererMessage
                    val payload = launch(start = CoroutineStart.LAZY) {
                        releasePayload.await()
                        edge.state.value = rendererMessagePayload(edge.index)
                    }
                    edge = RendererMessage(if (newer) 31 else 0, payload)
                    val originalItems = (30 downTo 1).map { RendererMessage(it) }
                    val original = RendererWindow(originalItems, hasNewer = true, hasOlder = true)
                    val model = RendererHistoryModel(original)
                    model.followsLatest.value = false
                    val viewState = AgentHistoryViewState()
                    val interactions = mutableListOf<ScrollInteraction>()
                    var demands = 0
                    try {
                        runMosaicTest {
                            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                                viewState.scrollInteractionSource.interactions.collect { interactions += it }
                            }
                            try {
                                setContentAndSnapshot {
                                    Column(Modifier.width(40).height(8)) {
                                        AgentHistoryView(model, RendererShellSessions, viewState)
                                    }
                                }
                                settleRendererFrames()
                                assertFalse(payload.isActive)
                                if (newer) viewState.listState.requestScrollToStart()
                                else viewState.listState.requestScrollToEnd()
                                settleRendererFrames()
                                val consumedLayout = viewState.listState.layoutInfo
                                val anchor = consumedLayout.visibleItemsInfo.first()

                                // Publication AFTER the actual logical request was consumed.
                                // Neither a new window nor its later height may repin that request.
                                val replacement = RendererWindow(
                                    if (newer) listOf(edge) + originalItems.dropLast(1)
                                    else originalItems.drop(1) + edge,
                                    hasNewer = true, hasOlder = true,
                                    onOlderDemand = { if (!newer) demands++ },
                                    onNewerDemand = { if (newer) demands++ },
                                )
                                assertFalse(edge in replacement.accessedItems)
                                model.historyItems.value = replacement
                                assertSame(consumedLayout, viewState.listState.layoutInfo)
                                settleRendererFrames()
                                assertTrue(edge in replacement.accessedItems)
                                assertTrue(payload.isActive && !payload.isCompleted)
                                assertTrue(edge.state.value is MessageHistoryItemState.Loading)
                                val loadingLayout = viewState.listState.layoutInfo
                                assertTrue(loadingLayout.visibleItemsInfo.none { it.key === edge })
                                assertSame(anchor.key, loadingLayout.visibleItemsInfo.first().key)
                                assertEquals(anchor.offset, loadingLayout.visibleItemsInfo.first().offset)
                                assertTrue(replacement.isMeasuredHistoryWindow(
                                    model, loadingLayout, viewState.listState.canScrollBackward,
                                ), "Provider identity does not imply edge payload/geometry readiness.")
                                assertTrue(replacement.isMeasuredHistoryWindow(
                                    model, loadingLayout, viewState.listState.canScrollBackward,
                                    requireReadyRows = true,
                                ), "Even ready visible rows do not load or reveal the offscreen edge.")
                                assertFalse(replacement.isMeasuredHistoryWindow(
                                    model, loadingLayout, viewState.listState.canScrollBackward,
                                    inputTarget = edge, expectedTargetHeight = 2,
                                ))
                                assertEquals(0, demands, "Overscan get is not visible-edge demand.")

                                releasePayload.complete(Unit)
                                payload.join()
                                assertTrue(edge.state.value is MessageHistoryItemState.Ready)
                                assertSame(loadingLayout, viewState.listState.layoutInfo)
                                assertFalse(replacement.isMeasuredHistoryWindow(
                                    model, loadingLayout, viewState.listState.canScrollBackward,
                                    inputTarget = edge, expectedTargetHeight = 2,
                                ), "Ready alone is still not measured input geometry.")
                                settleRendererFrames()
                                val grownLayout = viewState.listState.layoutInfo
                                assertSame(anchor.key, grownLayout.visibleItemsInfo.first().key)
                                assertEquals(anchor.offset, grownLayout.visibleItemsInfo.first().offset)
                                assertTrue(grownLayout.visibleItemsInfo.none { it.key === edge })
                                assertFalse(replacement.isMeasuredHistoryWindow(
                                    model, grownLayout, viewState.listState.canScrollBackward,
                                    inputTarget = edge, expectedTargetHeight = 2,
                                ), "An offscreen Ready payload must not authorize a visible-edge assertion.")
                                assertEquals(0, demands, "Faithful anchor preservation is not a paging failure.")

                                if (keyboard) {
                                    // Real pointer focus on a fully visible stored neighbour, not
                                    // Tab relocation onto the offscreen target or a fake key callback.
                                    val neighbour = grownLayout.visibleItemsInfo.first { row ->
                                        row.key is MessageHistoryItemViewModel && row.offset >= 0 &&
                                            row.offset + row.size <= grownLayout.viewportEndOffset
                                    }
                                    sendMouseEvent(MouseEvent(1, neighbour.offset, MouseEvent.Type.Press, MouseEvent.Button.Left))
                                    sendMouseEvent(MouseEvent(1, neighbour.offset, MouseEvent.Type.Release))
                                    settleRendererFrames()
                                    assertEquals(0, demands, "Acquiring focus must not stand in for Page input.")
                                    assertSame(anchor.key, viewState.listState.layoutInfo.visibleItemsInfo.first().key)
                                    assertEquals(anchor.offset, viewState.listState.layoutInfo.visibleItemsInfo.first().offset)
                                    sendKeyEvent(KeyboardEvent(if (newer) KeyboardEvent.PageDown else KeyboardEvent.PageUp))
                                } else {
                                    sendMouseEvent(MouseEvent(
                                        1, 1, MouseEvent.Type.Press,
                                        if (newer) MouseEvent.Button.WheelDown else MouseEvent.Button.WheelUp,
                                    ))
                                }
                                settleRendererFrames()
                                assertTrue(replacement.isMeasuredHistoryWindow(
                                    model, viewState.listState.layoutInfo, viewState.listState.canScrollBackward,
                                    inputTarget = edge, expectedTargetHeight = 2,
                                ), "Genuine continuation must reveal the Ready edge at its real height.")
                                assertEquals(1, demands)
                                assertTrue(interactions.any {
                                    it.source == (if (keyboard) ScrollInputSource.Keyboard else ScrollInputSource.Pointer) &&
                                        (if (newer) it.consumedDelta > 0 else it.consumedDelta < 0)
                                }, "The actual input must consume movement, not just enqueue an event.")
                                assertFalse(model.followsLatest.value, "This fixture still has newer stored history.")
                                settleRendererFrames()
                                assertEquals(1, demands, "Same mounted edge is one-shot, not a retry loop.")
                            } finally {
                                collector.cancel()
                                withContext(NonCancellable) { collector.join() }
                                cancel()
                            }
                        }
                    } finally {
                        releasePayload.cancel()
                        payload.cancel()
                        withContext(NonCancellable) { payload.join() }
                        model.close()
                    }
                }
            }
        }
    }

    for (newer in listOf(false, true)) {
        val direction = if (newer) "newer" else "older"
        test("published $direction window cannot borrow old provider readiness before anchored edge input") {
            var originalDemands = 0
            var replacementDemands = 0
            val originalItems = (30 downTo 1).map { RendererMessage(it) }
            val original = RendererWindow(
                originalItems, hasNewer = true, hasOlder = true,
                onOlderDemand = { if (!newer) originalDemands++ },
                onNewerDemand = { if (newer) originalDemands++ },
            )
            val model = RendererHistoryModel(original)
            val viewState = AgentHistoryViewState()
            try {
                runMosaicTest {
                    try {
                        setContentAndSnapshot {
                            Column(Modifier.width(40).height(8)) {
                                AgentHistoryView(model, RendererShellSessions, viewState)
                            }
                        }
                        settleRendererFrames()
                        repeat(2) {
                            sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
                        }
                        settleRendererFrames()
                        assertFalse(model.followsLatest.value, "Genuine input must leave follow before paging.")
                        val layout = viewState.listState.layoutInfo
                        val anchor = layout.visibleItemsInfo.first()
                        assertTrue(original.isMeasuredHistoryWindow(
                            model, layout, viewState.listState.canScrollBackward,
                        ))
                        model.loadState.value = AgentHistoryLoadState.LoadingOlder
                        assertFalse(original.isMeasuredHistoryWindow(
                            model, layout, viewState.listState.canScrollBackward,
                        ), "A lagging Ready emission cannot authorize an owner that is now loading.")
                        model.loadState.value = AgentHistoryLoadState.Ready
                        val demandsBefore = originalDemands
                        val replacement = RendererWindow(
                            if (newer) listOf(RendererMessage(31)) + originalItems.dropLast(1)
                            else originalItems.drop(1) + RendererMessage(0),
                            hasNewer = true, hasOlder = true,
                            onOlderDemand = { if (!newer) replacementDemands++ },
                            onNewerDemand = { if (newer) replacementDemands++ },
                        )

                        // Publication is deliberately before the next renderer frame. Count and
                        // every visible key still match the OLD candidate, exactly as a lagging
                        // combine collector can observe while the owner has already advanced.
                        model.historyItems.value = replacement
                        assertSame(layout, viewState.listState.layoutInfo)
                        assertFalse(original.isMeasuredHistoryWindow(
                            model, layout, viewState.listState.canScrollBackward,
                        ), "An emitted old window must not authorize navigation from the new model value.")
                        assertFalse(replacement.isMeasuredHistoryWindow(
                            model, layout, viewState.listState.canScrollBackward,
                        ), "Equal row counts are not provider identity.")

                        settleRendererFrames()
                        val measured = viewState.listState.layoutInfo
                        assertTrue(replacement.isMeasuredHistoryWindow(
                            model, measured, viewState.listState.canScrollBackward,
                        ))
                        val restored = measured.visibleItemsInfo.first()
                        assertSame(anchor.key, restored.key, "Publication alone must retain the actual anchor.")
                        assertEquals(anchor.offset, restored.offset)
                        assertEquals(demandsBefore, originalDemands, "No obsolete edge is requested.")
                        assertEquals(0, replacementDemands, "An anchored middle viewport is not edge demand.")

                        // Logical list intent is resolved by the provider used for measurement;
                        // never call requestOlder/requestNewer or a VM navigation command here.
                        if (newer) viewState.listState.requestScrollToStart()
                        else viewState.listState.requestScrollToEnd()
                        settleRendererFrames()
                        val edge = replacement.peek(if (newer) 0 else replacement.size - 1)
                        assertTrue(viewState.listState.layoutInfo.visibleItemsInfo.any { it.key === edge })
                        assertEquals(1, replacementDemands, "Only the actual rendered edge demands one page.")
                        assertFalse(model.followsLatest.value, "Programmatic edge positioning is not user intent.")
                    } finally {
                        cancel()
                    }
                }
            } finally {
                model.close()
            }
        }
    }

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
    override val hasOlder: Boolean = false,
    private val onOlderDemand: () -> Unit = {},
    private val onNewerDemand: () -> Unit = {},
) : HistoryItemWindow {
    val accessedItems = mutableSetOf<HistoryItemViewModel>()
    override val generation = 0L
    override val size: Int get() = items.size
    override fun peek(index: Int): HistoryItemViewModel = items[index]
    override fun get(index: Int): HistoryItemViewModel {
        val item = items[index]
        accessedItems += item
        // The real HistoryItemWindow.get starts its child's existing lazy Job; peek never does.
        ((item as? MessageHistoryItemViewModel)?.state?.value as? MessageHistoryItemState.Loading)
            ?.loadingJob?.start()
        return item
    }
    override fun requestOlder() = onOlderDemand()
    override fun requestNewer() = onNewerDemand()
}

private fun rendererMessagePayload(index: Int) = MessageHistoryItemState.Ready(
    StableUserMessage(listOf(ContentItem.InputText("row-$index"))), Duration.ZERO,
)

private class RendererMessage(
    override val index: Int,
    loadingJob: Job? = null,
) : MessageHistoryItemViewModel {
    override val state = MutableStateFlow<MessageHistoryItemState>(
        loadingJob?.let { MessageHistoryItemState.Loading(it) } ?: rendererMessagePayload(index),
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
