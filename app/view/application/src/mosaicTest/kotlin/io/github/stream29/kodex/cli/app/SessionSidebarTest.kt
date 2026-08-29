package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun expansionRequiresRoomForSidebarsAndContent() {
        assertFalse(canExpandSessionSidebar(columns = 28, oppositeExpanded = false))
        assertTrue(canExpandSessionSidebar(columns = 29, oppositeExpanded = false))
        assertFalse(canExpandSessionSidebar(columns = 56, oppositeExpanded = true))
        assertTrue(canExpandSessionSidebar(columns = 57, oppositeExpanded = true))
    }

    @Test
    fun shellSessionRowsWrapHardLines() {
        assertEquals(
            listOf("42: abcd", "efghijkl", "next"),
            shellSessionSidebarLines(
                sessionId = 42,
                command = "abcdefghijkl\nnext",
                columns = 8,
            ),
        )
    }
}

private class TestAgentShellSession : AgentShellSession {
    override val sessionId: Int = 1
    override val arguments: ExecCommandArguments = ExecCommandArguments(command = "sleep 1")
    override val completed: StateFlow<Boolean> = MutableStateFlow(false)
    var closeCount: Int = 0

    override fun close() {
        closeCount += 1
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
