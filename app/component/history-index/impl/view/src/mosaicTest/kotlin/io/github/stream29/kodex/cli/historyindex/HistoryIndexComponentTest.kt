package io.github.stream29.kodex.cli.historyindex

import io.github.stream29.kodex.cli.components.formatPopupTimestamp as formatHistoryIndexTimestamp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadHandle
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadState
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.TuiPopupAnchorBounds
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

val historyIndexRendererTest by testSuite {
    for ((name, state, expected) in listOf(
        Triple("loading", HistoryIndexReadState.Loading, "┌● …"),
        Triple("failed", HistoryIndexReadState.Failed, "┌● [error]"),
        Triple("ready", HistoryIndexReadState.Ready(entry(2, "row summary")), "┌● row summary"),
        Triple("closed", HistoryIndexReadState.Closed, ""),
    )) {
        test("row $name branch renders safely") {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Column(Modifier.width(28)) { HistoryIndexRowContent(state, "┌●") }
                }
                if (expected.isEmpty()) assertTrue(snapshot.isBlank(), snapshot)
                else assertTrue(expected in snapshot, snapshot)
            }
        }
    }
    test("row terminal-width ellipsis never wraps into another history row") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(12)) {
                    HistoryIndexRowContent(HistoryIndexReadState.Ready(entry(2, "a long summary with 界 characters")), "●")
                }
            }
            assertEquals(1, snapshot.lines().filter(String::isNotBlank).size)
            assertFalse("characters" in snapshot, snapshot)
            assertTrue("..." in snapshot || "…" in snapshot, snapshot)
        }
    }
    test("body renders oldest first connected sparse graph") {
        val vm = RenderIndex(listOf(entry(0, "Context compacted"), entry(2, "Question"), entry(5, "Answer")))
        runMosaicTest {
            val snapshot = setContentAndSnapshot { HistoryIndexSidebarBody(vm, 28, 4) }
            assertEquals(listOf("┌● Context compacted", "├● Question", "└● Answer"),
                snapshot.lines().map(String::trimEnd).filter(String::isNotEmpty))
            setContent { Text("unmounted") }
            awaitIndexCondition { vm.rowHandles.all { it.state.value == HistoryIndexReadState.Closed } }
        }
        assertTrue(vm.rowHandles.all { it.state.value == HistoryIndexReadState.Closed })
        assertTrue(vm.isActive) // Unmount released borrowed handles, not the shared child.
    }
    test("two sidebars acquire independent handles and unmounting one leaves the other alive") {
        val vm = RenderIndex(listOf(entry(2, "Shared")))
        var leftVisible by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                Row {
                    if (leftVisible) HistoryIndexSidebarBody(vm, 20, 2, side = HistoryIndexSide.Left)
                    HistoryIndexSidebarBody(vm, 20, 2, side = HistoryIndexSide.Right)
                }
            }
            assertEquals(2, vm.rowHandles.size)
            val (left, right) = vm.rowHandles
            assertNotSame(left, right)
            leftVisible = false
            awaitIndexCondition { left.state.value == HistoryIndexReadState.Closed }
            assertEquals(HistoryIndexReadState.Closed, left.state.value)
            assertTrue(right.state.value is HistoryIndexReadState.Ready)
            assertTrue(vm.isActive)
        }
    }
    test("pointer is renderer-local and row request captures exact child generation index revision") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        var request: HistoryIndexInteractionRequest? = null
        runMosaicTest {
            setContentAndSnapshot {
                HistoryIndexSidebarBody(vm, 20, 1, onHoverChanged = { target, hovered ->
                    if (hovered) request = target
                })
            }
            sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Motion))
            snapshotNow()
            assertEquals(IntOffset(4, 0), request?.pointerPosition)
            assertSame(vm, request?.viewModel)
            assertEquals(7, request?.index)
            assertEquals(11L, request?.generation)
            sendMouseEvent(MouseEvent(9, 0, MouseEvent.Type.Motion))
            snapshotNow()
            assertEquals(IntOffset(9, 0), request?.pointerPosition)
            val oldRequest = requireNotNull(request)
            vm.replaceSameIndex()
            snapshotNow()
            assertFalse(oldRequest.isCurrent())
        }
    }
    test("pointer following starts at latest pauses on scroll away and resumes at end") {
        val vm = RenderIndex(listOf(entry(0, "Oldest"), entry(1, "Question"), entry(2, "Thinking")))
        val list = LazyListState().apply { requestScrollToEnd() }
        runMosaicTest {
            setContentAndSnapshot { HistoryIndexSidebarBody(vm, 28, 2, listState = list) }
            awaitContaining("Thinking")
            vm.append(entry(3, "Answer"))
            val following = awaitContaining("Answer")
            assertFalse("Question" in following, following)
            assertFalse(list.canScrollForward)
            sendMouseEvent(MouseEvent(3, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            awaitContaining("Oldest")
            vm.append(entry(4, "New entry"))
            val older = snapshotNow()
            assertTrue("Oldest" in older, older)
            assertFalse("New entry" in older, older)
            assertTrue(list.canScrollForward)
            sendMouseEvent(MouseEvent(3, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            snapshotNow()
            sendMouseEvent(MouseEvent(3, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            awaitContaining("New entry")
            vm.append(entry(5, "Following again"))
            val resumed = awaitContaining("Following again")
            assertFalse("Oldest" in resumed, resumed)
            assertFalse(list.canScrollForward)
        }
    }
    for (kind in HistoryIndexEntryKind.entries) {
        test("hover Ready classification $kind renders full title and detail") {
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(70).height(12)) {
                        val anchor = rememberTuiPopupAnchor()
                        Text("entry", Modifier.tuiPopupAnchor(anchor))
                        HistoryIndexHoverContent(anchor, HistoryIndexSide.Left, null,
                            HistoryIndexReadState.Ready(HistoryIndexEntryDetail(kind, "Complete detail")),
                            60, 10, {})
                    }
                }
                val snapshot = awaitContaining("Complete detail")
                assertTrue(kind.displayName in snapshot, snapshot)
                assertFalse("Index:" in snapshot, snapshot)
            }
        }
    }
    for (state in listOf(HistoryIndexReadState.Loading, HistoryIndexReadState.Failed, HistoryIndexReadState.Closed)) {
        test("hover $state renders no stale detail or raw exception") {
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(70).height(8)) {
                        val anchor = rememberTuiPopupAnchor()
                        Text("entry", Modifier.tuiPopupAnchor(anchor))
                        HistoryIndexHoverContent(anchor, HistoryIndexSide.Left, null, state, 60, 7, {})
                    }
                }
                val snapshot = if (state == HistoryIndexReadState.Failed) awaitContaining("Unable to read") else snapshotNow()
                assertEquals(state == HistoryIndexReadState.Failed, "Error" in snapshot)
                assertFalse("raw-secret" in snapshot, snapshot)
            }
        }
    }
    test("hover uses shared read-only questions projection and never reveals secret answers") {
        val event = StableRequestUserInputToolEvent(
            callId = "request",
            arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("layout", "Layout", "Which layout?", options = listOf(
                    RequestUserInputQuestionOption("Compact", "Use less space"),
                    RequestUserInputQuestionOption("Detailed", "Show every field"),
                )),
                RequestUserInputQuestion("secret", "Credential", "Enter token", isSecret = true),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "layout" to RequestUserInputAnswer(listOf("Compact")),
                "secret" to RequestUserInputAnswer(listOf("never-render-secret")),
            ))),
        )
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(Modifier.width(65).height(12)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    HistoryIndexHoverContent(anchor, HistoryIndexSide.Left, null,
                        HistoryIndexReadState.Ready(HistoryIndexEntryDetail(
                            HistoryIndexEntryKind.RequestUserInput, "fallback", event)),
                        60, 11, {})
                }
            }
            val snapshot = awaitContaining("[hidden]")
            assertTrue("Layout: Which layout?" in snapshot, snapshot)
            assertTrue("[● Compact]" in snapshot, snapshot)
            assertTrue("Use less space" in snapshot, snapshot)
            assertFalse("Detailed" in snapshot, snapshot)
            assertFalse("never-render-secret" in snapshot, snapshot)
            assertFalse("fallback" in snapshot, snapshot)
        }
    }
    test("async hover delays acquisition and releases exact detail when host removes request") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        var visible by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(Modifier.width(45).height(8)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) { HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor) }
                    HistoryIndexHoverPopup(if (visible) request else null, 40, 7, {})
                }
            }
            assertEquals(0, vm.detailHandles.size)
            awaitContaining("Content")
            assertEquals(1, vm.detailHandles.size)
            visible = false
            val dismissed = snapshotNow()
            assertEquals(HistoryIndexReadState.Closed, vm.detailHandles.single().state.value)
            assertFalse("User message" in dismissed, dismissed)
        }
    }
    test("context menu timestamp Loading Ready null Ready value and Failed branches") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        vm.timestampState = HistoryIndexReadState.Loading
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(Modifier.width(70).height(8)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) {
                        HistoryIndexMenuRequest(HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor), null)
                    }
                    HistoryIndexContextMenu(request, {})
                }
            }
            val initial = awaitContaining("Check out")
            assertTrue("Index: 7" in initial, initial)
            assertFalse("Timestamp:" in initial, initial)
            assertFalse("Revert" in initial, initial)
            assertFalse("Fork" in initial, initial)
            val handle = vm.timestampHandles.single()
            handle.state.value = HistoryIndexReadState.Ready(null)
            assertFalse("Timestamp:" in snapshotNow())
            handle.state.value = HistoryIndexReadState.Ready(Instant.parse("2026-10-03T01:02:03Z"))
            assertTrue("UTC" in awaitContaining("Timestamp:"))
            handle.state.value = HistoryIndexReadState.Failed
            assertFalse("Timestamp:" in snapshotNow())
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            snapshotNow()
            assertEquals(listOf(7), vm.scrolled)
            assertTrue(vm.isActive)
            setContent { Text("unmounted") }
            awaitIndexCondition { handle.state.value == HistoryIndexReadState.Closed }
        }
        assertEquals(HistoryIndexReadState.Closed, vm.timestampHandles.single().state.value)
    }
    test("same-index local revision invalidates an old menu without retargeting Check out") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        var dismissals = 0
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(Modifier.width(40).height(6)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) {
                        HistoryIndexMenuRequest(HistoryIndexInteractionRequest(HistoryIndexSide.Right, vm, 11, 7, anchor), null)
                    }
                    HistoryIndexContextMenu(request, { dismissals++ })
                }
            }
            awaitContaining("Check out")
            vm.replaceSameIndex()
            val closed = snapshotNow()
            assertFalse("Check out" in closed, closed)
            assertTrue(dismissals > 0)
            assertTrue(vm.scrolled.isEmpty())
        }
    }
    test("timestamp formatting includes seconds and exact zone offset") {
        val time = Instant.parse("2026-10-03T01:02:03Z")
        assertEquals("2026-10-03 01:02:03 UTC+00:00", formatHistoryIndexTimestamp(time, TimeZone.UTC))
        assertEquals("2026-10-03 09:02:03 UTC+08:00", formatHistoryIndexTimestamp(time, TimeZone.of("Asia/Singapore")))
    }
    test("menu helper honors pointer opening position and exposes only information and Check out") {
        var checkedOut = 0
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(Modifier.width(70).height(10)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    HistoryIndexContextMenuPopup(anchor, IntOffset(5, 2), 42, {}, { checkedOut++ })
                }
            }
            val snapshot = awaitContaining("Index: 42")
            assertEquals(5, snapshot.lines()[2].indexOf("[Index: 42"), snapshot)
            assertFalse("Timestamp:" in snapshot, snapshot)
            assertFalse("Revert" in snapshot, snapshot)
            assertFalse("Fork" in snapshot, snapshot)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            snapshotNow()
            assertEquals(1, checkedOut)
        }
    }
    test("both popup sides and terminal boundaries use pointer geometry only") {
        val anchor = TuiPopupAnchorBounds(IntOffset(20, 2), IntSize(10, 1))
        val size = IntSize(80, 24)
        val popup = IntSize(15, 4)
        assertEquals(IntOffset(25, 3), HistoryIndexHoverPositionProvider(
            HistoryIndexSide.Left, 60, 20, IntOffset(4, 0)).calculatePosition(anchor, size, popup))
        assertEquals(IntOffset(9, 3), HistoryIndexHoverPositionProvider(
            HistoryIndexSide.Right, 60, 20, IntOffset(4, 0)).calculatePosition(anchor, size, popup))
        val bottom = TuiPopupAnchorBounds(IntOffset(79, 23), IntSize(1, 1))
        assertEquals(IntOffset(64, 19), HistoryIndexHoverPositionProvider(
            HistoryIndexSide.Left, 60, 20, null).calculatePosition(bottom, size, popup))
    }
}

private fun entry(index: Int, text: String) = HistoryIndexEntry(index, HistoryIndexEntryKind.UserMessage, text)

private class RenderHandle<T>(
    override val generation: Long,
    override val index: Int,
    initial: HistoryIndexReadState<T>,
) : HistoryIndexReadHandle<T> {
    override val state = MutableStateFlow(initial)
    override fun release() { state.value = HistoryIndexReadState.Closed }
}

private class RenderIndex(entries: List<HistoryIndexEntry>) : HistoryIndexViewModel {
    private val entries = entries.associateByTo(linkedMapOf()) { it.index }
    override val window = MutableStateFlow(HistoryIndexWindow(11, this.entries.keys.toList()))
    override var isActive = true
    val rowHandles = mutableListOf<RenderHandle<HistoryIndexEntry>>()
    val detailHandles = mutableListOf<RenderHandle<HistoryIndexEntryDetail>>()
    val timestampHandles = mutableListOf<RenderHandle<Instant?>>()
    var timestampState: HistoryIndexReadState<Instant?> = HistoryIndexReadState.Ready(null)
    val scrolled = mutableListOf<Int>()
    override fun contains(generation: Long, index: Int) = isActive && generation == window.value.generation && index in entries
    override suspend fun load(generation: Long, index: Int) = entries.getValue(index)
    override suspend fun loadDetail(generation: Long, index: Int) =
        entries.getValue(index).let { HistoryIndexEntryDetail(it.kind, it.summary) }
    override suspend fun readMessageTimestamp(generation: Long, index: Int): Instant? = null
    override fun acquireRow(generation: Long, index: Int) =
        RenderHandle(generation, index, HistoryIndexReadState.Ready(entries.getValue(index))).also { rowHandles += it }
    override fun acquireDetail(generation: Long, index: Int) =
        RenderHandle(generation, index, HistoryIndexReadState.Ready(
            entries.getValue(index).let { HistoryIndexEntryDetail(it.kind, it.summary) })).also { detailHandles += it }
    override fun acquireTimestamp(generation: Long, index: Int) =
        RenderHandle(generation, index, timestampState).also { timestampHandles += it }
    override fun checkOut(generation: Long, index: Int): Boolean {
        if (!contains(generation, index)) return false
        scrolled += index
        return true
    }
    override fun close() {
        isActive = false
        (rowHandles + detailHandles + timestampHandles).forEach { it.release() }
    }
    fun append(entry: HistoryIndexEntry) {
        entries[entry.index] = entry
        window.value = window.value.copy(indexes = window.value.indexes + entry.index)
    }
    fun replaceSameIndex() {
        (rowHandles + detailHandles + timestampHandles).forEach { it.release() }
        window.value = window.value.copy(revision = window.value.revision + 1)
    }
}

private suspend fun TestMosaic<String>.snapshotNow(): String =
    try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
        draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    }

private suspend fun TestMosaic<String>.awaitContaining(text: String): String {
    var snapshot = ""
    repeat(5) {
        snapshot = snapshotNow()
        if (text in snapshot) return snapshot
    }
    assertTrue(text in snapshot, snapshot)
    return snapshot
}

private suspend fun TestMosaic<String>.awaitIndexCondition(condition: () -> Boolean) {
    repeat(100) {
        if (condition()) return
        try { awaitSnapshot(50.milliseconds) } catch (_: TimeoutCancellationException) { }
    }
    assertTrue(condition(), draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false))
}
