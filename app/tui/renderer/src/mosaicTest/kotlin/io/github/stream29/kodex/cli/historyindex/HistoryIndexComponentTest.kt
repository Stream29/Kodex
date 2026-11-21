package io.github.stream29.kodex.cli.historyindex

import io.github.stream29.kodex.cli.app.mosaicIndexHover
import io.github.stream29.kodex.cli.app.mosaicIndexMenu
import io.github.stream29.kodex.cli.app.rememberMosaicIndexOverlayRenderer
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
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
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

val historyIndexRendererTest by testSuite {
    test("child parent rows appear once before and after sparse entries in a narrow Index") {
        val vm = RenderIndex(emptyList())
        val returned = mutableListOf<HistoryIndexParentAnchor>()
        var completed by mutableStateOf(false)
        val navigation = HistoryIndexNavigation(vm, { _, _ -> error("No creation row") }, returned::add) { completed }
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    HistoryIndexSidebarBody(vm, columns = 24, rows = 6, navigation = navigation)
                }
            }
            val before = awaitContaining("Return to creation")
            assertFalse("Return to completion" in before)
            vm.append(entry(3, "After pagination"))
            assertFalse("Return to completion" in awaitContaining("After pagination"))
            completed = true
            val snapshot = awaitContaining("Return to completion")
            assertEquals(1, snapshot.lines().count { "Return to creation" in it })
            assertEquals(1, snapshot.lines().count { "Return to completion" in it })
            for (anchor in HistoryIndexParentAnchor.entries) {
                val y = snapshot.lines().indexOfFirst { anchor.label in it }
                assertTrue(y >= 0)
                sendMouseEvent(MouseEvent(2, y, MouseEvent.Type.Press, MouseEvent.Button.Left))
                sendMouseEvent(MouseEvent(2, y, MouseEvent.Type.Release, MouseEvent.Button.Left))
                snapshotNow()
            }
            assertEquals(HistoryIndexParentAnchor.entries.toList(), returned)
        }
    }

    test("creation-row hover stays outside the right-aligned Enter and Enter does not scroll the parent") {
        val vm = RenderIndex(listOf(entry(7, "Created #91").copy(createdChildAgentId = 91)))
        val opened = mutableListOf<Pair<HistoryIndexTarget, Int>>()
        val navigation = HistoryIndexNavigation(vm, { target, child -> opened.add(target to child) })
        var hovering by mutableStateOf<HistoryIndexInteractionRequest?>(null)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    TuiPopupHost(Modifier.width(60).height(12)) {
                        Column {
                            Text("Header")
                            HistoryIndexSidebarBody(
                                vm, columns = 26, rows = 6, navigation = navigation,
                                onHoverChanged = { request, entered -> if (entered) hovering = request },
                            )
                        }
                        val current = hovering
                        HistoryIndexHoverContent(
                            interaction = remember(current) { current?.let(::mosaicIndexHover) },
                            renderer = rememberMosaicIndexOverlayRenderer(34, 11),
                            onHoverChanged = {},
                            onDismiss = { hovering = null },
                        )
                    }
                }
            }
            val snapshot = awaitContaining("[Enter]")
            val y = snapshot.lines().indexOfFirst { "[Enter]" in it }
            val row = snapshot.lines()[y]
            assertTrue("Created #91" in row)
            assertEquals(26, row.trimEnd().length)
            val x = row.indexOf("[Enter]") + 2
            sendMouseEvent(MouseEvent(3, y, MouseEvent.Type.Motion))
            val visible = awaitContaining("User message")
            assertEquals(27, visible.lines()[y].indexOf("User message"), visible)
            assertTrue("[Enter]" in visible.lines()[y], visible)
            sendMouseEvent(MouseEvent(10, y, MouseEvent.Type.Motion))
            val moved = snapshotNow()
            assertEquals(27, moved.lines()[y].indexOf("User message"), moved)
            sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Release, MouseEvent.Button.Left))
            snapshotNow()
            assertEquals(7, opened.single().first.index)
            assertSame(vm, opened.single().first.viewModel)
            assertEquals(91, opened.single().second)
            assertTrue(vm.scrolled.isEmpty())
        }
    }

    test("editable keyboard menu runs the supplied action before its owner dismisses") {
        val vm = RenderIndex(listOf(entry(7, "Full message body").copy(editableUserText = "Original text")))
        var visible by mutableStateOf(false)
        val events = mutableListOf<String>()
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    TuiPopupHost(Modifier.width(60).height(12)) {
                        val anchor = rememberTuiPopupAnchor()
                        Text("entry", Modifier.tuiPopupAnchor(anchor))
                        val request = remember(anchor) {
                            mosaicIndexMenu(HistoryIndexMenuRequest(
                                HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor), null,
                            ))
                        }
                        HistoryIndexMenuContent(
                            if (visible) request else null,
                            rememberMosaicIndexOverlayRenderer(0, 0),
                            canEdit = true,
                            edit = { command, target, text ->
                                assertTrue(visible)
                                assertSame(vm, target.viewModel)
                                assertEquals(7, target.index)
                                assertNull(text)
                                events += command.name
                                visible = false
                            },
                        ) { events += "dismiss"; visible = false }
                    }
                }
            }
            visible = true
            val menu = awaitContaining("Revert and edit")
            assertTrue("Go to" in menu && "Revert to here" in menu && "Fork from here" in menu, menu)
            assertFalse("Details" in menu, menu)
            assertFalse("Full message body" in menu, menu)
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            snapshotNow()
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            assertFalse("Revert to here" in snapshotNow())
            assertEquals(listOf(HistoryIndexCommand.Revert.name), events)
            assertTrue(vm.scrolled.isEmpty())
        }
    }

    test("hover scrolls full Agent message and keeps its heading") {
        val body = "Author: root\nRecipient: child\n" + (0..80).joinToString("\n") { "detail-line-$it" }
        val vm = RenderIndex(listOf(entry(7, "Message").copy(
            detail = HistoryIndexEntryDetail(HistoryIndexEntryKind.AgentMessage, body),
        )))
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    TuiPopupHost(Modifier.width(50).height(10)) {
                        val anchor = rememberTuiPopupAnchor()
                        Text("entry", Modifier.tuiPopupAnchor(anchor))
                        val request = remember(anchor) {
                            mosaicIndexHover(HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor))
                        }
                        HistoryIndexHoverContent(request, rememberMosaicIndexOverlayRenderer(50, 8), {}, {})
                    }
                }
            }
            val initial = awaitContaining("detail-line-0")
            assertTrue("Agent message" in initial, initial)
            assertTrue("Author: root" in initial && "Recipient: child" in initial, initial)
            val lines = initial.lines()
            val bodyY = lines.indexOfFirst { "detail-line-0" in it }
            val bodyX = lines[bodyY].indexOf("detail-line-0") + 1
            repeat(3) {
                sendMouseEvent(MouseEvent(bodyX, bodyY, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
                snapshotNow()
            }
            val scrolled = awaitContaining("detail-line-8")
            assertFalse("detail-line-0" in scrolled, scrolled)
            assertTrue("Agent message" in scrolled, scrolled)
            assertTrue(vm.scrolled.isEmpty())
        }
    }

    test("row ready branch renders safely") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    Column(Modifier.width(28)) {
                        MosaicHistoryIndexRowText(
                            historyIndexRowPresentation(entry(2, "row summary"), HistoryIndexRowPosition.Single),
                            hovered = false,
                            pressed = false,
                            graphOverride = "┌●",
                        )
                    }
                }
            }
            assertTrue("┌● row summary" in snapshot, snapshot)
        }
    }

    test("row terminal-width ellipsis never wraps into another history row") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    Column(Modifier.width(12)) {
                        MosaicHistoryIndexRowText(
                            historyIndexRowPresentation(
                                entry(2, "a long summary with 界 characters"),
                                HistoryIndexRowPosition.Single,
                            ),
                            hovered = false,
                            pressed = false,
                            graphOverride = "●",
                        )
                    }
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
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { HistoryIndexSidebarBody(vm, 28, 4) }
            }
            assertEquals(listOf("┌● Context compacted", "├● Question", "└● Answer"),
                snapshot.lines().map(String::trimEnd).filter(String::isNotEmpty))
            setContent {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { Text("unmounted") }
            }
        }
        assertTrue(vm.isActive)
    }
    test("single row preserves its literal summary and single-point graph") {
        val vm = RenderIndex(listOf(entry(7, "┌● user text")))
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { HistoryIndexSidebarBody(vm, 28, 2) }
            }
            assertEquals(listOf("● ┌● user text"), snapshot.lines().map(String::trimEnd).filter(String::isNotEmpty))
        }
    }
    test("two sidebars share window and unmounting one leaves the other alive") {
        val vm = RenderIndex(listOf(entry(2, "Shared")))
        var leftVisible by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                Row {
                    if (leftVisible) HistoryIndexSidebarBody(vm, 20, 2, side = HistoryIndexSide.Left)
                    HistoryIndexSidebarBody(vm, 20, 2, side = HistoryIndexSide.Right)
                }
            }
            }
            leftVisible = false
            val snapshot = snapshotNow()
            assertTrue("Shared" in snapshot, snapshot)
            assertTrue(vm.isActive)
        }
    }
    test("moving within the row preserves the exact hover request and its target becomes stale after revision") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        var request: HistoryIndexInteractionRequest? = null
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                HistoryIndexSidebarBody(vm, 20, 1, onHoverChanged = { target, hovered ->
                    if (hovered) request = target
                })
            }
            }
            sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Motion))
            snapshotNow()
            val oldRequest = assertNotNull(request)
            assertSame(vm, oldRequest.viewModel)
            assertEquals(7, oldRequest.index)
            assertEquals(11L, oldRequest.generation)
            sendMouseEvent(MouseEvent(9, 0, MouseEvent.Type.Motion))
            snapshotNow()
            assertSame(oldRequest, request)
            vm.replaceSameIndex()
            snapshotNow()
            assertFalse(oldRequest.isCurrent())
        }
    }
    test("pointer following starts at latest pauses on scroll away and resumes at end") {
        val vm = RenderIndex(listOf(entry(0, "Oldest"), entry(1, "Question"), entry(2, "Thinking")))
        val list = LazyListState().apply { requestScrollToEnd() }
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { HistoryIndexSidebarBody(vm, 28, 2, listState = list) }
            }
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
        test("hover classification $kind renders full title and detail") {
            val vm = RenderIndex(listOf(entry(7, "Summary").copy(detail = HistoryIndexEntryDetail(kind, "Complete detail"))))
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    TuiPopupHost(Modifier.width(70).height(12)) {
                        val anchor = rememberTuiPopupAnchor()
                        Text("entry", Modifier.tuiPopupAnchor(anchor))
                        val request = remember(anchor) {
                            mosaicIndexHover(HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor))
                        }
                        HistoryIndexHoverContent(request, rememberMosaicIndexOverlayRenderer(60, 10), {}, {})
                    }
                }
                }
                val snapshot = awaitContaining("Complete detail")
                assertTrue(expectedIndexKindName(kind) in snapshot, snapshot)
                assertTrue("Index: 7" in snapshot, snapshot)
                assertFalse("Timestamp:" in snapshot, snapshot)
            }
        }
    }
    test("hover renders typed Plan explanation and all step statuses instead of fallback") {
        val plan = StablePlanUpdate("plan", arguments = UpdatePlanArgs("Explanation", listOf(
            PlanItemArg("Pending step", StepStatus.Pending),
            PlanItemArg("Current step", StepStatus.InProgress),
            PlanItemArg("Finished step", StepStatus.Completed),
        )))
        val vm = RenderIndex(listOf(entry(7, "Plan").copy(
            detail = HistoryIndexEntryDetail(HistoryIndexEntryKind.PlanUpdate, "raw fallback", plan = plan),
        )))
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    TuiPopupHost(Modifier.width(65).height(12)) {
                        val anchor = rememberTuiPopupAnchor()
                        Text("entry", Modifier.tuiPopupAnchor(anchor))
                        val request = remember(anchor) {
                            mosaicIndexHover(HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor))
                        }
                        HistoryIndexHoverContent(request, rememberMosaicIndexOverlayRenderer(60, 11), {}, {})
                    }
                }
            }
            val snapshot = awaitContaining("[x] Finished step")
            assertTrue("Explanation" in snapshot && "[ ] Pending step" in snapshot && "[>] Current step" in snapshot, snapshot)
            assertFalse("raw fallback" in snapshot, snapshot)
        }
    }
    test("hover uses shared read-only questions projection and never reveals secret answers") {
        val event = StableAskUserEvent(
            askId = 1,
            arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("layout", "Layout", "Which layout?", options = listOf(
                    RequestUserInputQuestionOption("Compact", "Use less space"),
                    RequestUserInputQuestionOption("Detailed", "Show every field"),
                )),
                RequestUserInputQuestion("secret", "Credential", "Enter token", isSecret = true),
            )),
            response = RequestUserInputResponse(mapOf(
                "layout" to RequestUserInputAnswer(listOf("Compact")),
                "secret" to RequestUserInputAnswer(listOf("never-render-secret")),
            )),
        )
        val timestamp = Instant.parse("2026-10-03T01:02:03Z")
        val vm = RenderIndex(listOf(entry(7, "Question").copy(
            detail = HistoryIndexEntryDetail(HistoryIndexEntryKind.RequestUserInput, "fallback", event),
            timestamp = timestamp,
        )))
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(Modifier.width(65).height(12)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) {
                        mosaicIndexHover(HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor))
                    }
                    HistoryIndexHoverContent(request, rememberMosaicIndexOverlayRenderer(60, 11), {}, {})
                }
            }
            }
            val snapshot = awaitContaining("[hidden]")
            assertTrue("Index: 7" in snapshot, snapshot)
            assertTrue("Timestamp: ${formatHistoryIndexTimestamp(timestamp, TimeZone.currentSystemDefault())}" in snapshot, snapshot)
            assertTrue("Layout: Which layout?" in snapshot, snapshot)
            assertTrue("[● Compact]" in snapshot, snapshot)
            assertTrue("Use less space" in snapshot, snapshot)
            assertFalse("Detailed" in snapshot, snapshot)
            assertFalse("never-render-secret" in snapshot, snapshot)
            assertFalse("fallback" in snapshot, snapshot)
        }
    }
    test("async hover delays display and hides when host removes request") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        var visible by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(Modifier.width(45).height(8)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) { HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor) }
                    HistoryIndexHoverContent(
                        if (visible) mosaicIndexHover(request) else null,
                        rememberMosaicIndexOverlayRenderer(40, 7),
                        {},
                        {},
                    )
                }
            }
            }
            awaitContaining("Content")
            visible = false
            val dismissed = snapshotNow()
            assertFalse("User message" in dismissed, dismissed)
        }
    }
    test("context menu timestamp null and value branches") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(Modifier.width(70).height(8)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) {
                        HistoryIndexMenuRequest(HistoryIndexInteractionRequest(HistoryIndexSide.Left, vm, 11, 7, anchor), null)
                    }
                    val opening = remember(request) { mosaicIndexMenu(request) }
                    HistoryIndexMenuContent(opening, rememberMosaicIndexOverlayRenderer(0, 0)) {}
                }
            }
            }
            val initial = awaitContaining("Go to")
            assertTrue("Index: 7" in initial, initial)
            assertFalse("Timestamp:" in initial, initial)
            assertFalse("Revert" in initial, initial)
            assertFalse("Fork" in initial, initial)
            val timestamp = Instant.parse("2026-10-03T01:02:03Z")
            vm.updateEntry(entry(7, "Content").copy(timestamp = timestamp))
            awaitContaining("Timestamp: ${formatHistoryIndexTimestamp(timestamp, TimeZone.currentSystemDefault())}")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            snapshotNow()
            assertEquals(listOf(7), vm.scrolled)
            assertTrue(vm.isActive)
        }
    }
    test("same-index local revision invalidates an old menu without retargeting Go to") {
        val vm = RenderIndex(listOf(entry(7, "Content")))
        var dismissals = 0
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(Modifier.width(40).height(6)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) {
                        HistoryIndexMenuRequest(HistoryIndexInteractionRequest(HistoryIndexSide.Right, vm, 11, 7, anchor), null)
                    }
                    val opening = remember(request) { mosaicIndexMenu(request) }
                    HistoryIndexMenuContent(opening, rememberMosaicIndexOverlayRenderer(0, 0)) { dismissals++ }
                }
            }
            }
            awaitContaining("Go to")
            vm.replaceSameIndex()
            val closed = snapshotNow()
            assertFalse("Go to" in closed, closed)
            assertTrue(dismissals > 0)
            assertTrue(vm.scrolled.isEmpty())
        }
    }
    test("timestamp formatting includes seconds and exact zone offset") {
        val time = Instant.parse("2026-10-03T01:02:03Z")
        assertEquals("2026-10-03 01:02:03 UTC+00:00", formatHistoryIndexTimestamp(time, TimeZone.UTC))
        assertEquals("2026-10-03 09:02:03 UTC+08:00", formatHistoryIndexTimestamp(time, TimeZone.of("Asia/Singapore")))
    }
    test("menu helper honors pointer opening position and exposes only information and Go to") {
        var checkedOut = 0
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(Modifier.width(70).height(10)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", Modifier.tuiPopupAnchor(anchor))
                    HistoryIndexContextMenuPopup(
                        anchor, IntOffset(5, 2), 42, null,
                        listOf(HistoryIndexMenuAction("GoTo", "Go to") { checkedOut++ }), {},
                    )
                }
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
}

private fun entry(index: Int, text: String) = HistoryIndexEntry(index, HistoryIndexEntryKind.UserMessage, text)

private class RenderIndex(entries: List<HistoryIndexEntry>) : HistoryIndexViewModel {
    private val entries = entries.associateByTo(linkedMapOf()) { it.index }
    override val window = MutableStateFlow(HistoryIndexWindow(11, this.entries.values.toList()))
    override var isActive = true
    val scrolled = mutableListOf<Int>()
    override fun contains(generation: Long, index: Int) = isActive && generation == window.value.generation && index in entries
    override fun checkOut(generation: Long, index: Int): Boolean {
        if (!contains(generation, index)) return false
        scrolled += index
        return true
    }
    override fun close() {
        isActive = false
    }
    fun append(entry: HistoryIndexEntry) {
        entries[entry.index] = entry
        window.value = window.value.copy(entries = entries.values.toList())
    }
    fun updateEntry(entry: HistoryIndexEntry) {
        entries[entry.index] = entry
        window.value = window.value.copy(entries = entries.values.toList())
    }
    fun replaceSameIndex() {
        window.value = window.value.copy(revision = window.value.revision + 1)
    }
}

private suspend fun TestMosaic<String>.snapshotNow(): String =
    try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
        draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    }

private suspend fun TestMosaic<String>.awaitContaining(text: String, stage: String = text): String {
    var snapshot = ""
    repeat(5) {
        snapshot = snapshotNow()
        if (text in snapshot) return snapshot
    }
    assertTrue(text in snapshot, "$stage: expected $text\n$snapshot")
    return snapshot
}

private fun expectedIndexKindName(kind: HistoryIndexEntryKind): String = when (kind) {
    HistoryIndexEntryKind.CompactionPoint -> "Compaction point"
    HistoryIndexEntryKind.UserMessage -> "User message"
    HistoryIndexEntryKind.AssistantMessage -> "Assistant message"
    HistoryIndexEntryKind.AssistantCommentary -> "Assistant commentary"
    HistoryIndexEntryKind.AssistantFinal -> "Assistant final"
    HistoryIndexEntryKind.DeveloperMessage -> "Developer message"
    HistoryIndexEntryKind.AgentMessage -> "Agent message"
    HistoryIndexEntryKind.RequestUserInput -> "Request user input"
    HistoryIndexEntryKind.PlanUpdate -> "Plan update"
}
