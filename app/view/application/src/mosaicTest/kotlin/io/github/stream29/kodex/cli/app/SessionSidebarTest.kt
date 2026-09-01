package io.github.stream29.kodex.cli.app

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
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.DefaultTuiColorScheme
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.roundToInt

class SessionSidebarTest {
    @Test
    fun noneSidebarShowsAnEmptyBody() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                SessionSidebar(
                    side = SessionSidebarSide.Left,
                    content = SidebarContent.None,
                    selectedAgent = null,
                    dropdownState = rememberTuiDropdownState(),
                    columns = 28,
                    rows = 8,
                    onHoverChanged = {},
                    onToggleExpanded = {},
                    onOpenShellSessionMenu = {},
                )
            }

            assertTrue("[←]" in snapshot)
            assertTrue("None" in snapshot)
            assertFalse("Terminal sessions" in snapshot)
        }
    }

    @Test
    fun expandedSidebarsUseMirroredDirectionButtons() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Row {
                    SessionSidebar(
                        side = SessionSidebarSide.Left,
                        content = SidebarContent.None,
                        selectedAgent = null,
                        dropdownState = rememberTuiDropdownState(),
                        columns = 28,
                        rows = 2,
                        onHoverChanged = {},
                        onToggleExpanded = {},
                        onOpenShellSessionMenu = {},
                    )
                    SessionSidebar(
                        side = SessionSidebarSide.Right,
                        content = SidebarContent.None,
                        selectedAgent = null,
                        dropdownState = rememberTuiDropdownState(),
                        columns = 28,
                        rows = 2,
                        onHoverChanged = {},
                        onToggleExpanded = {},
                        onOpenShellSessionMenu = {},
                    )
                }
            }

            val header = snapshot.lines().first()
            assertTrue(header.startsWith("[←]"), header)
            assertTrue(header.endsWith("[→]"), header)
        }
    }

    @Test
    fun collapsedSidebarsUseMirroredDirectionButtons() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Row {
                    SessionSidebarExpandButton(
                        side = SessionSidebarSide.Left,
                        onHoverChanged = {},
                        onExpand = {},
                    )
                    SessionSidebarExpandButton(
                        side = SessionSidebarSide.Right,
                        onHoverChanged = {},
                        onExpand = {},
                    )
                }
            }

            assertEquals(listOf("[→][←]"), snapshot.lines())
            assertEquals(
                SessionSidebarCollapsedButtonColumns * 2,
                snapshot.lines().maxOf(String::length),
            )
            assertEquals(SessionSidebarCollapsedButtonRows, snapshot.lines().size)
        }
    }

    @Test
    fun terminalSessionsCanRenderOnBothSides() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Row {
                    SessionSidebar(
                        side = SessionSidebarSide.Left,
                        content = SidebarContent.TerminalSessions,
                        selectedAgent = null,
                        dropdownState = rememberTuiDropdownState(),
                        columns = 28,
                        rows = 2,
                        onHoverChanged = {},
                        onToggleExpanded = {},
                        onOpenShellSessionMenu = {},
                    )
                    SessionSidebar(
                        side = SessionSidebarSide.Right,
                        content = SidebarContent.TerminalSessions,
                        selectedAgent = null,
                        dropdownState = rememberTuiDropdownState(),
                        columns = 28,
                        rows = 2,
                        onHoverChanged = {},
                        onToggleExpanded = {},
                        onOpenShellSessionMenu = {},
                    )
                }
            }

            assertEquals(2, "Terminal sessions".toRegex().findAll(snapshot).count())
        }
    }

    @Test
    fun titleMenuSelectsTerminalSessions() = runTest {
        var selected by mutableStateOf(SidebarContent.None)
        var dropdownState: TuiDropdownState? = null
        runMosaicTest {
            setContentAndSnapshot {
                val dropdown = rememberTuiDropdownState()
                dropdownState = dropdown
                TuiPopupHost(modifier = Modifier.width(28).height(6)) {
                    SessionSidebar(
                        side = SessionSidebarSide.Left,
                        content = selected,
                        selectedAgent = null,
                        dropdownState = dropdown,
                        columns = 28,
                        rows = 6,
                        onHoverChanged = {},
                        onToggleExpanded = {},
                        onOpenShellSessionMenu = {},
                    )
                    SessionSidebarContentMenu(
                        dropdownState = dropdown,
                        selected = selected,
                        onSelect = { selected = it },
                    )
                }
            }
            dropdownState?.expand()
            awaitSnapshotContaining("Terminal sessions")

            sendKeyEvent(KeyboardEvent(codepoint = 57353))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[Terminal sessions]")
        }

        assertEquals(SidebarContent.TerminalSessions, selected)
        assertFalse(dropdownState?.expanded ?: true)
    }

    @Test
    fun terminalSessionMenuClosesItsSession() = runTest {
        val session = TestAgentShellSession()
        runMosaicTest {
            setContentAndSnapshot {
                val anchor = rememberTuiPopupAnchor()
                TuiPopupHost(modifier = Modifier.width(28).height(5)) {
                    Text("terminal", modifier = Modifier.tuiPopupAnchor(anchor))
                    ShellSessionContextMenu(
                        request = ShellSessionMenuRequest(
                            session = session,
                            anchor = anchor,
                            clickPosition = null,
                        ),
                        onDismissRequest = {},
                    )
                }
            }
            awaitSnapshotContaining("Close session")

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
        }

        assertEquals(1, session.closeCount)
    }

    @Test
    fun terminalSessionRowsUseDistinctSingleLineBulletsWithoutIds() = runTest {
        runMosaicTest {
            setContentAndSnapshot {
                Column(modifier = Modifier.width(12)) {
                    ShellSessionSidebarRow(
                        side = SessionSidebarSide.Left,
                        session = TestAgentShellSession(
                            sessionId = 41,
                            command = "first",
                        ),
                        columns = 12,
                        onHoverChanged = { _, _ -> },
                        onOpenMenu = { _, _ -> },
                    )
                    ShellSessionSidebarRow(
                        side = SessionSidebarSide.Left,
                        session = TestAgentShellSession(
                            sessionId = 42,
                            command = "abcdefghijklmnop",
                        ),
                        columns = 12,
                        onHoverChanged = { _, _ -> },
                        onOpenMenu = { _, _ -> },
                    )
                }
            }

            val snapshot = awaitSnapshot()
            assertTrue("● first" in snapshot, snapshot)
            assertTrue("● abcdefg..." in snapshot, snapshot)
            assertFalse("41" in snapshot, snapshot)
            assertFalse("42" in snapshot, snapshot)
            assertEquals(2, snapshot.lines().count { line -> "● " in line }, snapshot)
        }
    }

    @Test
    fun terminalSessionHoverShowsIdAndCompleteCommand() = runTest {
        val session = TestAgentShellSession(
            sessionId = 42,
            command = "echo first\necho second",
        )
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(40).height(8)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("● echo...", modifier = Modifier.tuiPopupAnchor(anchor))
                    val request = remember(anchor) {
                        ShellSessionInteractionRequest(
                            side = SessionSidebarSide.Left,
                            session = session,
                            anchor = anchor,
                        )
                    }
                    ShellSessionHoverPopup(
                        request = request,
                        contentColumns = 30,
                        contentRows = 7,
                        onHoverChanged = {},
                        onDismissRequest = {},
                    )
                }
            }

            val snapshot = awaitSnapshotContaining("echo second")
            assertTrue("Session 42" in snapshot, snapshot)
            assertTrue("echo first" in snapshot, snapshot)
        }
    }

    @Test
    fun historyIndexRendersAConnectedOldestFirstTimeline() = runTest {
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(0, HistoryIndexEntryKind.CompactionPoint, "Context compacted"),
                HistoryIndexEntry(2, HistoryIndexEntryKind.UserMessage, "Question"),
                HistoryIndexEntry(5, HistoryIndexEntryKind.AssistantFinal, "Answer"),
            ),
        )
        runMosaicTest {
            setContentAndSnapshot {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 28,
                    rows = 4,
                )
            }
            val snapshot = awaitSnapshotContaining("Answer")

            assertEquals(
                listOf(
                    "┌● Context compacted",
                    "├● Question",
                    "└● Answer",
                ),
                snapshot.lines().map(String::trimEnd).filter(String::isNotEmpty),
            )
        }
    }

    @Test
    fun historyIndexContextMenuShowsIndexAndChecksOut() = runTest {
        var checkOutCount = 0
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(30).height(5)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", modifier = Modifier.tuiPopupAnchor(anchor))
                    HistoryIndexContextMenuPopup(
                        anchor = anchor,
                        clickPosition = null,
                        index = 42,
                        onDismissRequest = {},
                        onCheckOut = { checkOutCount += 1 },
                    )
                }
            }
            val snapshot = awaitSnapshotContaining("Check out")
            assertTrue("Index: 42" in snapshot, snapshot)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
        }

        assertEquals(1, checkOutCount)
    }

    @Test
    fun historyIndexHoverIsOpaqueAndFollowsThePointer() = runTest {
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(
                    index = 7,
                    kind = HistoryIndexEntryKind.UserMessage,
                    summary = "Complete content",
                ),
            ),
        )
        var request: HistoryIndexInteractionRequest? = null
        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(40).height(8)) {
                    val anchor = rememberTuiPopupAnchor()
                    Column {
                        Text("entry", modifier = Modifier.tuiPopupAnchor(anchor))
                        repeat(7) {
                            Text("x".repeat(40))
                        }
                    }
                    val current = remember(anchor) {
                        HistoryIndexInteractionRequest(
                            side = SessionSidebarSide.Left,
                            viewModel = viewModel,
                            generation = 0,
                            index = 7,
                            anchor = anchor,
                        ).also {
                            it.pointerPosition = IntOffset(x = 4, y = 0)
                        }
                    }
                    request = current
                    HistoryIndexHoverPopup(
                        request = current,
                        contentColumns = 30,
                        contentRows = 7,
                        onHoverChanged = {},
                    )
                }
            }
            val snapshot = awaitSnapshotContaining("Complete content")
            assertTrue("User message" in snapshot, snapshot)
            assertFalse("Index: 7" in snapshot, snapshot)
            assertEquals(5, snapshot.lines()[1].indexOf("User message"), snapshot)
            assertTrue("User message    x" in snapshot.lines()[1], snapshot)

            request?.pointerPosition = IntOffset(x = 10, y = 0)
            val moved = awaitSnapshot()
            assertEquals(11, moved.lines()[1].indexOf("User message"), moved)
        }
    }

    @Test
    fun historyIndexRowTracksTheLastPointerPosition() = runTest {
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(
                    index = 7,
                    kind = HistoryIndexEntryKind.UserMessage,
                    summary = "Content",
                ),
            ),
        )
        var request: HistoryIndexInteractionRequest? = null
        runMosaicTest {
            setContentAndSnapshot {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 20,
                    rows = 1,
                    onHoverChanged = { current, hovered ->
                        if (hovered) request = current
                    },
                )
            }

            sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Motion))
            awaitSnapshot()
            assertEquals(IntOffset(x = 4, y = 0), request?.pointerPosition)

            sendMouseEvent(MouseEvent(9, 0, MouseEvent.Type.Motion))
            awaitSnapshot()
            assertEquals(IntOffset(x = 9, y = 0), request?.pointerPosition)
        }
    }

    @Test
    fun requestUserInputHoverReusesTheHistoryReadOnlyForm() = runTest {
        val event = StableRequestUserInputToolEvent(
            callId = "request",
            arguments = RequestUserInputArgs(
                questions = listOf(
                    RequestUserInputQuestion(
                        id = "layout",
                        header = "Layout",
                        question = "Which layout should be used?",
                        options = listOf(
                            RequestUserInputQuestionOption("Compact", "Use less space"),
                            RequestUserInputQuestionOption("Detailed", "Show every field"),
                        ),
                    ),
                    RequestUserInputQuestion(
                        id = "note",
                        header = "Note",
                        question = "Anything else?",
                    ),
                ),
            ),
            result = StableRequestUserInputResult.Answered(
                RequestUserInputResponse(
                    answers = mapOf(
                        "layout" to RequestUserInputAnswer(listOf("Compact")),
                        "note" to RequestUserInputAnswer(listOf("Keep it readable")),
                    ),
                ),
            ),
        )
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(
                    index = 7,
                    kind = HistoryIndexEntryKind.RequestUserInput,
                    summary = "Which layout should be used?",
                ),
            ),
            details = mapOf(
                7 to HistoryIndexEntryDetail(
                    kind = HistoryIndexEntryKind.RequestUserInput,
                    content = "fallback",
                    requestUserInput = event,
                ),
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", modifier = Modifier.tuiPopupAnchor(anchor))
                    HistoryIndexHoverPopup(
                        request = HistoryIndexInteractionRequest(
                            side = SessionSidebarSide.Left,
                            viewModel = viewModel,
                            generation = 0,
                            index = 7,
                            anchor = anchor,
                        ),
                        contentColumns = 50,
                        contentRows = 11,
                        onHoverChanged = {},
                    )
                }
            }
            val snapshot = awaitSnapshotContaining("Keep it readable")
            assertTrue("Layout: Which layout should be used?" in snapshot, snapshot)
            assertTrue("[● Compact]" in snapshot, snapshot)
            assertTrue("  Use less space" in snapshot, snapshot)
            assertTrue("Note: Anything else?" in snapshot, snapshot)
            assertTrue("  > Keep it readable" in snapshot, snapshot)
            assertFalse("Detailed" in snapshot, snapshot)
            assertFalse("Options:" in snapshot, snapshot)
            assertFalse("Answer:" in snapshot, snapshot)
        }
    }

    @Test
    fun historyIndexInitiallyFollowsTheLatestEntry() = runTest {
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(0, HistoryIndexEntryKind.CompactionPoint, "Context compacted"),
                HistoryIndexEntry(1, HistoryIndexEntryKind.UserMessage, "Question"),
                HistoryIndexEntry(2, HistoryIndexEntryKind.AssistantCommentary, "Thinking"),
            ),
        )
        val listState = LazyListState().apply { requestScrollToEnd() }
        runMosaicTest {
            setContentAndSnapshot {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 28,
                    rows = 2,
                    listState = listState,
                )
            }
            awaitSnapshotContaining("Thinking")

            viewModel.append(
                HistoryIndexEntry(3, HistoryIndexEntryKind.AssistantFinal, "Answer"),
            )
            val snapshot = awaitSnapshotContaining("Answer")
            assertTrue("Question" !in snapshot, snapshot)
            assertFalse(listState.canScrollForward)
        }
    }

    @Test
    fun historyIndexStopsFollowingWhileReadingOlderEntries() = runTest {
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(0, HistoryIndexEntryKind.CompactionPoint, "Context compacted"),
                HistoryIndexEntry(1, HistoryIndexEntryKind.UserMessage, "Question"),
                HistoryIndexEntry(2, HistoryIndexEntryKind.AssistantCommentary, "Thinking"),
            ),
        )
        val listState = LazyListState().apply { requestScrollToEnd() }
        runMosaicTest {
            setContentAndSnapshot {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 28,
                    rows = 2,
                    listState = listState,
                )
            }
            awaitSnapshotContaining("Thinking")

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
            awaitSnapshotContaining("Context compacted")
            viewModel.append(
                HistoryIndexEntry(3, HistoryIndexEntryKind.AssistantFinal, "Answer"),
            )
            val readingOlder = awaitSnapshot()
            assertTrue("Context compacted" in readingOlder, readingOlder)
            assertTrue("Answer" !in readingOlder, readingOlder)
            assertTrue(listState.canScrollForward)

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
            awaitSnapshotContaining("Answer")
            viewModel.append(
                HistoryIndexEntry(4, HistoryIndexEntryKind.AgentMessage, "Agent body"),
            )
            val followingAgain = awaitSnapshotContaining("Agent body")
            assertTrue("Context compacted" !in followingAgain, followingAgain)
            assertFalse(listState.canScrollForward)
        }
    }

    @Test
    fun splitterUsesBackgroundStateColorsWithoutAGlyph() = runTest {
        val scheme = DefaultTuiColorScheme
        val ansiSnapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
        }
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val idle = setContentAndSnapshot {
                TuiTheme(colorScheme = scheme) {
                    Row {
                        SessionSidebar(
                            side = SessionSidebarSide.Left,
                            content = SidebarContent.None,
                            selectedAgent = null,
                            dropdownState = rememberTuiDropdownState(),
                            columns = 8,
                            rows = 2,
                            onHoverChanged = {},
                            onToggleExpanded = {},
                            onOpenShellSessionMenu = {},
                        )
                        Text("M")
                    }
                }
            }
            assertFalse(scheme.surfaceContainerHover.backgroundEscape() in idle, idle)
            assertFalse(scheme.surfaceContainerActive.backgroundEscape() in idle, idle)
            assertFalse("│" in idle || "|" in idle, idle)

            sendMouseEvent(MouseEvent(7, 1, MouseEvent.Type.Motion))
            val hovered = awaitSnapshot()
            assertTrue(scheme.surfaceContainerHover.backgroundEscape() in hovered, hovered)

            sendMouseEvent(MouseEvent(7, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            val active = awaitSnapshot()
            assertTrue(scheme.surfaceContainerActive.backgroundEscape() in active, active)

            sendMouseEvent(MouseEvent(7, 1, MouseEvent.Type.Release))
            awaitSnapshot()
        }
    }

    @Test
    fun splitterTracksCapturedPointerWhileItsBoundaryMoves() = runTest {
        var columns by mutableStateOf(8)
        val started = mutableListOf<Int>()
        val resized = mutableListOf<Int>()
        val ended = mutableListOf<Int>()

        runMosaicTest {
            setContentAndSnapshot {
                SessionSidebar(
                    side = SessionSidebarSide.Left,
                    content = SidebarContent.None,
                    selectedAgent = null,
                    dropdownState = rememberTuiDropdownState(),
                    columns = columns,
                    rows = 2,
                    onHoverChanged = {},
                    onToggleExpanded = {},
                    onOpenShellSessionMenu = {},
                    onResizeStart = started::add,
                    onResize = { requested ->
                        resized += requested
                        columns = requested
                    },
                    onResizeEnd = ended::add,
                )
            }

            sendMouseEvent(MouseEvent(7, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(11, 1, MouseEvent.Type.Drag, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(13, 1, MouseEvent.Type.Drag, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(13, 1, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals(listOf(8), started)
        assertEquals(listOf(12, 14, 14), resized)
        assertEquals(listOf(14), ended)
        assertEquals(14, columns)
    }

    @Test
    fun shiftDragIsNotConsumedByTheSplitter() = runTest {
        val events = mutableListOf<Int>()
        runMosaicTest {
            setContentAndSnapshot {
                SessionSidebar(
                    side = SessionSidebarSide.Left,
                    content = SidebarContent.None,
                    selectedAgent = null,
                    dropdownState = rememberTuiDropdownState(),
                    columns = 8,
                    rows = 2,
                    onHoverChanged = {},
                    onToggleExpanded = {},
                    onOpenShellSessionMenu = {},
                    onResizeStart = events::add,
                    onResize = events::add,
                    onResizeEnd = events::add,
                )
            }

            sendMouseEvent(
                MouseEvent(
                    x = 7,
                    y = 1,
                    type = MouseEvent.Type.Press,
                    button = MouseEvent.Button.Left,
                    shift = true,
                ),
            )
            awaitSnapshot()
            sendMouseEvent(
                MouseEvent(
                    x = 10,
                    y = 1,
                    type = MouseEvent.Type.Drag,
                    button = MouseEvent.Button.Left,
                    shift = true,
                ),
            )
            awaitSnapshot()
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun expansionRequiresRoomForSidebarsAndContent() {
        assertFalse(
            canExpandSessionSidebar(
                columns = 28,
                requestedColumns = 28,
                oppositeColumns = 0,
            ),
        )
        assertTrue(
            canExpandSessionSidebar(
                columns = 29,
                requestedColumns = 28,
                oppositeColumns = 0,
            ),
        )
        assertFalse(
            canExpandSessionSidebar(
                columns = 56,
                requestedColumns = 21,
                oppositeColumns = 35,
            ),
        )
        assertTrue(
            canExpandSessionSidebar(
                columns = 57,
                requestedColumns = 21,
                oppositeColumns = 35,
            ),
        )
    }

    @Test
    fun layoutAndResizeKeepOneMainContentColumn() {
        assertEquals(
            SessionSidebarColumns(left = 25, content = 1, right = 25),
            resolveSessionSidebarColumns(
                columns = 51,
                leftColumns = 30,
                rightColumns = 30,
            ),
        )
        assertEquals(
            29,
            clampSessionSidebarResize(
                columns = 50,
                oppositeColumns = 20,
                requestedColumns = 40,
            ),
        )
        assertEquals(
            4,
            clampSessionSidebarResize(
                columns = 50,
                oppositeColumns = 20,
                requestedColumns = 2,
            ),
        )
        assertEquals(
            null,
            clampSessionSidebarResize(
                columns = 4,
                oppositeColumns = 0,
                requestedColumns = 4,
            ),
        )
    }

    @Test
    fun shellSessionSummaryFlattensAndTruncatesCommands() {
        assertEquals(
            "● abc...",
            shellSessionSidebarSummary(command = "abcdefghijkl\nnext", columns = 8),
        )
        assertEquals(
            "● ab cd",
            shellSessionSidebarSummary(command = "ab\ncd", columns = 10),
        )
        assertEquals(
            "●",
            shellSessionSidebarSummary(command = "command", columns = 1),
        )
    }
}

private class TestAgentShellSession(
    override val sessionId: Int = 1,
    command: String = "sleep 1",
) : AgentShellSession {
    override val arguments: ExecCommandArguments = ExecCommandArguments(command = command)
    override val completed: StateFlow<Boolean> = MutableStateFlow(false)
    var closeCount: Int = 0

    override fun close() {
        closeCount += 1
    }
}

private class TestHistoryIndexViewModel(
    entries: List<HistoryIndexEntry>,
    private val details: Map<Int, HistoryIndexEntryDetail> = emptyMap(),
) : HistoryIndexViewModel {
    private val mutableEntries = entries.associateByTo(linkedMapOf()) { entry -> entry.index }
    override val window = MutableStateFlow(
        HistoryIndexWindow(
            generation = 0,
            indexes = entries.map(HistoryIndexEntry::index),
        ),
    )

    override fun contains(generation: Long, index: Int): Boolean =
        generation == window.value.generation && index in mutableEntries

    override suspend fun load(generation: Long, index: Int): HistoryIndexEntry =
        requireNotNull(mutableEntries[index])

    override suspend fun loadDetail(
        generation: Long,
        index: Int,
    ): HistoryIndexEntryDetail {
        details[index]?.let { return it }
        val entry = load(generation, index)
        return HistoryIndexEntryDetail(entry.kind, entry.summary)
    }

    fun append(entry: HistoryIndexEntry) {
        mutableEntries[entry.index] = entry
        window.value = window.value.copy(indexes = window.value.indexes + entry.index)
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}

private fun Color.backgroundEscape(): String {
    val (red, green, blue) = this
    return "48;2;${(red * 255).roundToInt()};${(green * 255).roundToInt()};" +
        "${(blue * 255).roundToInt()}"
}
