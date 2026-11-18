package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.historyindex.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.test.startRpcFrontendFixture
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
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
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.math.roundToInt

private const val TestCollapsedButtonColumns: Int = SessionSidebarCollapsedButtonColumns
private const val TestCollapsedButtonRows: Int = SessionSidebarCollapsedButtonRows

val sessionSidebarTest by testSuite {
    for ((columns, rows) in listOf(30 to 9, 76 to 16)) {
        test("terminal output dialog follows, pages, and returns to the original menu at ${columns}x$rows") {
            val fixture = startRpcFrontendFixture(this)
            try {
                val real = requireNotNull(fixture.create().rootAgent.value)
                val session = TestAgentShellSession(7, "long test command " + "argument ".repeat(15), "读取输出 🧪")
                var source = (1..64).joinToString("") { "sample $it 界界 ${"cell".repeat(13)}\n" }.encodeToByteArray()
                session.read = { offset, limit ->
                    val start = offset.toInt()
                    val end = minOf(source.size, start + limit.toInt())
                    StdoutSlice(source.copyOfRange(start, end), byteArrayOf(), 0u, offset, end.toULong(), source.size.toULong())
                }
                val registry = MutableStateFlow<Map<Int, AgentShellSession>>(mapOf(7 to session))
                val agent = object : AgentViewModel by real {
                    override val shellSessions = object : AgentShellSessionRegistry { override val activeSessions = registry }
                }
                var fallback = 0
                runMosaicTest {
                    setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                            val state = rememberApplicationOverlays(
                                agent, SidebarContent.TerminalSessions, SidebarContent.None, ApplicationPopupState.Closed,
                            )
                            val anchor = rememberTuiPopupAnchor()
                            LaunchedEffect(anchor) {
                                snapshotFlow { anchor.isPlaced }.first { it }
                                state.openMenu(ApplicationOverlayMenu.Shell(
                                    SessionSidebarSide.Left, mosaicShellMenu(ShellSessionMenuRequest(session, anchor, null)),
                                ))
                            }
                            TuiPopupHost(Modifier.width(columns).height(rows)) {
                                Text("fixture sidebar", Modifier.tuiPopupAnchor(anchor))
                                ApplicationSidebarOverlaysContent(
                                    state, agent, rememberMosaicSidebarOverlayRenderer(columns, rows),
                                    rememberMosaicIndexOverlayRenderer(columns, rows),
                                    onShellOutputFallback = { fallback++ },
                                )
                            }
                        }
                    }
                    awaitSnapshotContaining("View output")
                    assertEquals(0, session.readCount)
                    sendKeyEvent(KeyboardEvent(13))
                    val latest = awaitSnapshotContaining("cell")
                    assertTrue("读取输出" in latest)
                    sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
                    val paused = awaitSnapshotContaining("[↓]")
                    assertTrue("读取输出" in paused)
                    sendKeyEvent(KeyboardEvent(27))
                    awaitSnapshotContaining("View output")
                    val stopped = session.readCount
                    delay(225)
                    assertEquals(stopped, session.readCount)
                    sendKeyEvent(KeyboardEvent(13))
                    awaitSnapshotContaining("读取输出")
                    source += "FINAL🙂\n".encodeToByteArray()
                    session.exitCode.value = 23
                    session.completed.value = true
                    val completed = awaitSnapshotContaining("Exit code 23")
                    assertTrue("FINAL" in completed, completed)
                    assertTrue("读取输出" in completed, completed)
                    sendKeyEvent(KeyboardEvent(27))
                    awaitSnapshotContaining("fixture sidebar")
                    assertEquals(1, fallback)
                    assertEquals(0, session.closeCount)
                }
            } finally { fixture.closeAndJoin() }
        }
    }

    test("collapsedButtonLayoutConstantsRemainCompileTimeConstants") {
        assertEquals(3, TestCollapsedButtonColumns)
        assertEquals(1, TestCollapsedButtonRows)
    }

    test("noneSidebarShowsAnEmptyBody") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
            }

            assertTrue("[←]" in snapshot)
            assertTrue("None" in snapshot)
            assertFalse("Terminal sessions" in snapshot)
        }
    }

    test("expandedSidebarsUseMirroredDirectionButtons") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
            }

            val header = snapshot.lines().first()
            assertTrue(header.startsWith("[←]"), header)
            assertTrue(header.endsWith("[→]"), header)
        }
    }

    test("collapsedSidebarsUseMirroredDirectionButtons") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
            }

            assertEquals(listOf("[→][←]"), snapshot.lines())
            assertEquals(
                SessionSidebarCollapsedButtonColumns * 2,
                snapshot.lines().maxOf(String::length),
            )
            assertEquals(SessionSidebarCollapsedButtonRows, snapshot.lines().size)
        }
    }

    test("terminalSessionsCanRenderOnBothSides") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
            }

            assertEquals(2, "Terminal sessions".toRegex().findAll(snapshot).count())
        }
    }

    test("titleMenuSelectsTerminalSessions") {
        var selected by mutableStateOf(SidebarContent.None)
        var dropdownState: TuiDropdownState? = null
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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

    test("terminalSessionMenuClosesItsSession") {
        val session = TestAgentShellSession()
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                val anchor = rememberTuiPopupAnchor()
                TuiPopupHost(modifier = Modifier.width(28).height(5)) {
                    Text("terminal", modifier = Modifier.tuiPopupAnchor(anchor))
                    SidebarShellMenuContent(
                        opening = mosaicShellMenu(
                            ShellSessionMenuRequest(
                                session = session,
                                anchor = anchor,
                                clickPosition = null,
                            ),
                        ),
                        renderer = rememberMosaicSidebarOverlayRenderer(0, 0),
                        onDismiss = {},
                    )
                }
            }
            }
            awaitSnapshotContaining("Close session")

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
        }

        assertEquals(1, session.closeCount)
    }

    test("terminalSessionRowsUseDistinctSingleLineBulletsWithoutIds") {
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(modifier = Modifier.width(12).height(4)) {
                    val renderer = rememberMosaicSidebarOverlayRenderer(12, 1)
                    Column(modifier = Modifier.width(12)) {
                        ShellSessionSidebarContent(
                            side = SessionSidebarSide.Left,
                            session = TestAgentShellSession(
                                sessionId = 41,
                                command = "command-one",
                                name = "first",
                            ),
                            renderer = renderer,
                        )
                        ShellSessionSidebarContent(
                            side = SessionSidebarSide.Left,
                            session = TestAgentShellSession(
                                sessionId = 42,
                                command = "command-two",
                                name = "abcdefghijklmnop",
                            ),
                            renderer = renderer,
                        )
                    }
                }
            }
            }

            val snapshot = awaitSnapshot()
            assertTrue("● first" in snapshot, snapshot)
            assertTrue("● abcdefg..." in snapshot, snapshot)
            assertFalse("41" in snapshot, snapshot)
            assertFalse("42" in snapshot, snapshot)
            assertFalse("command-" in snapshot, snapshot)
            assertEquals(2, snapshot.lines().count { line -> "● " in line }, snapshot)
        }
    }

    test("terminal hover uses the unnamed fallback and still shows the complete command") {
        val session = TestAgentShellSession(
            sessionId = 42,
            command = "echo first\necho second",
        )
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
                    SidebarShellHoverContent(
                        interaction = mosaicShellHover(request),
                        renderer = rememberMosaicSidebarOverlayRenderer(30, 7),
                        onHoverChanged = {},
                        onDismiss = {},
                    )
                }
            }
            }

            val snapshot = awaitSnapshotContaining("echo second")
            assertTrue("Terminal #42" in snapshot, snapshot)
            assertTrue("echo first" in snapshot, snapshot)
        }
    }

    test("historyIndexRendersAConnectedOldestFirstTimeline") {
        val viewModel = TestHistoryIndexViewModel(
            entries = listOf(
                HistoryIndexEntry(0, HistoryIndexEntryKind.CompactionPoint, "Context compacted"),
                HistoryIndexEntry(2, HistoryIndexEntryKind.UserMessage, "Question"),
                HistoryIndexEntry(5, HistoryIndexEntryKind.AssistantFinal, "Answer"),
            ),
        )
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 28,
                    rows = 4,
                )
            }
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

    test("historyIndexContextMenuShowsIndexAndChecksOut") {
        var checkOutCount = 0
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
            }
            val snapshot = awaitSnapshotContaining("Check out")
            assertTrue("Index: 42" in snapshot, snapshot)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
        }

        assertEquals(1, checkOutCount)
    }

    test("historyIndexHoverIsOpaqueAndFollowsThePointer") {
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
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
                            side = HistoryIndexSide.Left,
                            viewModel = viewModel,
                            generation = 0,
                            index = 7,
                            anchor = anchor,
                        ).also {
                            it.pointerPosition = IntOffset(x = 4, y = 0)
                        }
                    }
                    request = current
                    HistoryIndexHoverContent(
                        interaction = mosaicIndexHover(current),
                        renderer = rememberMosaicIndexOverlayRenderer(30, 7),
                        onHoverChanged = {},
                        onDismiss = {},
                    )
                }
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

    test("historyIndexRowTracksTheLastPointerPosition") {
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
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 20,
                    rows = 1,
                    onHoverChanged = { current, hovered ->
                        if (hovered) request = current
                    },
                )
            }
            }

            sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Motion))
            awaitSnapshot()
            assertEquals(IntOffset(x = 4, y = 0), request?.pointerPosition)

            sendMouseEvent(MouseEvent(9, 0, MouseEvent.Type.Motion))
            awaitSnapshot()
            assertEquals(IntOffset(x = 9, y = 0), request?.pointerPosition)
        }
    }

    test("requestUserInputHoverReusesTheHistoryReadOnlyForm") {
        val event = StableAskUserEvent(
            askId = 1,
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
            response = RequestUserInputResponse(
                answers = mapOf(
                    "layout" to RequestUserInputAnswer(listOf("Compact")),
                    "note" to RequestUserInputAnswer(listOf("Keep it readable")),
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
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    val anchor = rememberTuiPopupAnchor()
                    Text("entry", modifier = Modifier.tuiPopupAnchor(anchor))
                    HistoryIndexHoverContent(
                        interaction = mosaicIndexHover(
                            HistoryIndexInteractionRequest(
                                side = HistoryIndexSide.Left,
                                viewModel = viewModel,
                                generation = 0,
                                index = 7,
                                anchor = anchor,
                            ),
                        ),
                        renderer = rememberMosaicIndexOverlayRenderer(50, 11),
                        onHoverChanged = {},
                        onDismiss = {},
                    )
                }
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

    test("historyIndexInitiallyFollowsTheLatestEntry") {
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
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 28,
                    rows = 2,
                    listState = listState,
                )
            }
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

    test("historyIndexStopsFollowingWhileReadingOlderEntries") {
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
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                HistoryIndexSidebarBody(
                    viewModel = viewModel,
                    columns = 28,
                    rows = 2,
                    listState = listState,
                )
            }
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

    test("splitterUsesBackgroundStateColorsWithoutAGlyph") {
        val scheme = DefaultTuiColorScheme
        val ansiSnapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
        }
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val idle = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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

    test("splitterTracksCapturedPointerWhileItsBoundaryMoves") {
        var columns by mutableStateOf(8)
        val started = mutableListOf<Int>()
        val resized = mutableListOf<Int>()
        val ended = mutableListOf<Int>()

        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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

    test("shiftDragIsNotConsumedByTheSplitter") {
        val events = mutableListOf<Int>()
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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

    test("expansionRequiresRoomForSidebarsAndContent") {
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

    test("layoutAndResizeKeepOneMainContentColumn") {
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

    test("shellSessionSummaryFlattensAndTruncatesCommands") {
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

    test("two sidebars borrow one registry and independently hide completed processes") {
        val fixture = startRpcFrontendFixture(this)
        try {
            val real = requireNotNull(fixture.create().rootAgent.value)
            val first = TestAgentShellSession(1, "raw first", "first")
            val second = TestAgentShellSession(2, "raw second", "second")
            val retained = TestAgentShellSession(3, "raw retained", "retained").apply { completed.value = true }
            val sessions = MutableStateFlow<Map<Int, AgentShellSession>>(
                linkedMapOf(2 to second, 3 to retained, 1 to first),
            )
            val agent = object : AgentViewModel by real {
                override val shellSessions = object : AgentShellSessionRegistry {
                    override val activeSessions: StateFlow<Map<Int, AgentShellSession>> = sessions
                }
            }
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    Row {
                        SessionSidebar(
                            SessionSidebarSide.Left, SidebarContent.TerminalSessions, agent,
                            rememberTuiDropdownState(), 28, 6, {}, {}, {},
                        )
                        SessionSidebar(
                            SessionSidebarSide.Right, SidebarContent.TerminalSessions, agent,
                            rememberTuiDropdownState(), 28, 6, {}, {}, {},
                        )
                    }
                }
                }
                val both = awaitSnapshotContaining("second")
                assertEquals(2, Regex("● first").findAll(both).count(), both)
                assertEquals(2, Regex("● second").findAll(both).count(), both)
                assertTrue(both.indexOf("● first") < both.indexOf("● second"), both)
                assertFalse("retained" in both, both)
                first.completed.value = true
                var hidden = awaitSnapshot()
                repeat(5) { if ("● first" in hidden) hidden = awaitSnapshot() }
                assertFalse("● first" in hidden, hidden)
                assertEquals(2, Regex("● second").findAll(hidden).count(), hidden)
            }
            // UI filtering and unmounting do not delete/close execution-owned registry entries.
            assertEquals(3, sessions.value.size)
            assertSame(first, sessions.value[1])
            assertEquals(0, first.closeCount)
            assertEquals(0, second.closeCount)
            assertEquals(0, retained.closeCount)
        } finally { fixture.closeAndJoin() }
    }

    test("an open Shell menu acts on its captured handle even when an equal id is replaced") {
        val captured = TestAgentShellSession(7, "old")
        val replacement = TestAgentShellSession(7, "new")
        var selected: AgentShellSession by mutableStateOf(captured)
        var menuOpen by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                val anchor = rememberTuiPopupAnchor()
                val request = remember(anchor) { ShellSessionMenuRequest(captured, anchor, null) }
                TuiPopupHost(Modifier.width(28).height(5)) {
                    Text("${selected.arguments.command} menu=$menuOpen", Modifier.tuiPopupAnchor(anchor))
                    SidebarShellMenuContent(
                        opening = if (menuOpen) mosaicShellMenu(request) else null,
                        renderer = rememberMosaicSidebarOverlayRenderer(0, 0),
                        onDismiss = { menuOpen = false },
                    )
                }
            }
            }
            awaitSnapshotContaining("Close session")
            selected = replacement
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshotContaining("menu=false")
        }
        assertEquals(1, captured.closeCount)
        assertEquals(0, replacement.closeCount)
    }

    test("completion dismisses a captured Shell menu without executing Close") {
        val session = TestAgentShellSession()
        var dismissals = 0
        var menuOpen by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                val anchor = rememberTuiPopupAnchor()
                val request = remember(anchor) { ShellSessionMenuRequest(session, anchor, null) }
                TuiPopupHost(Modifier.width(28).height(5)) {
                    Text("terminal menu=$menuOpen", Modifier.tuiPopupAnchor(anchor))
                    SidebarShellMenuContent(
                        opening = if (menuOpen) mosaicShellMenu(request) else null,
                        renderer = rememberMosaicSidebarOverlayRenderer(0, 0),
                        onDismiss = {
                            dismissals++
                            menuOpen = false
                        },
                    )
                }
            }
            }
            awaitSnapshotContaining("Close session")
            session.completed.value = true
            val hidden = awaitSnapshotContaining("menu=false")
            assertFalse("Close session" in hidden, hidden)
        }
        assertEquals(1, dismissals)
        assertEquals(0, session.closeCount)
    }
}

private class TestAgentShellSession(
    override val sessionId: Int = 1,
    command: String = "sleep 1",
    name: String? = null,
) : AgentShellSession {
    override val arguments: ExecCommandArguments =
        ExecCommandArguments(command = command, name = name, yieldTimeMillis = 10_000)
    override val completed = MutableStateFlow(false)
    override val exitCode = MutableStateFlow<Int?>(null)
    var read: suspend (ULong, UInt) -> StdoutSlice = { _, _ -> error("Unexpected shell read") }
    var readCount = 0
        private set
    override suspend fun readOutput(offset: ULong, limit: UInt): StdoutSlice {
        readCount++
        return read(offset, limit)
    }
    var closeCount: Int = 0

    override fun close() {
        closeCount += 1
    }
}

private class TestHistoryIndexViewModel(
    entries: List<HistoryIndexEntry>,
    private val details: Map<Int, HistoryIndexEntryDetail> = emptyMap(),
) : HistoryIndexViewModel {
    override var isActive = true
        private set

    private val mutableEntries = entries.associateTo(linkedMapOf()) { entry ->
        val resolved = details[entry.index]?.let { entry.copy(detail = it) } ?: entry
        resolved.index to resolved
    }
    override val window = MutableStateFlow(
        HistoryIndexWindow(
            generation = 0,
            entries = mutableEntries.values.toList(),
        ),
    )

    override fun contains(generation: Long, index: Int): Boolean =
        isActive && generation == window.value.generation && index in mutableEntries

    override fun checkOut(generation: Long, index: Int): Boolean = contains(generation, index)
    override fun close() { isActive = false }

    fun append(entry: HistoryIndexEntry) {
        val resolved = details[entry.index]?.let { entry.copy(detail = it) } ?: entry
        mutableEntries[resolved.index] = resolved
        window.value = window.value.copy(entries = window.value.entries + resolved)
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
    assertTrue(expected in latest, "Expected '$expected' in:\n$latest")
    return latest
}

private fun Color.backgroundEscape(): String {
    val (red, green, blue) = this
    return "48;2;${(red * 255).roundToInt()};${(green * 255).roundToInt()};" +
        "${(blue * 255).roundToInt()}"
}
