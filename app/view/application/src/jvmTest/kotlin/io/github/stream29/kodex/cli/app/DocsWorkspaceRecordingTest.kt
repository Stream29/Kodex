package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val docsWorkspaceRecordingTest by testSuite {
    test("terminal row hover and close route to the exact fixture handle") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val real = fixture.persistedSession("Terminal example").rootAgent
            val shell = object : AgentShellSession {
                override val sessionId = 42
                override val arguments = ExecCommandArguments(
                    command = "printf 'offline fixture\\n'\nsleep 30",
                )
                override val completed = MutableStateFlow(false)
                override fun close() { completed.value = true }
            }
            val registry = object : AgentShellSessionRegistry {
                override val activeSessions = MutableStateFlow<Map<Int, AgentShellSession>>(mapOf(42 to shell))
            }
            val model = object : AgentViewModel by real {
                override val shellSessions = registry
            }
            var hover by mutableStateOf<ShellSessionInteractionRequest?>(null)
            var menu by mutableStateOf<ShellSessionMenuRequest?>(null)
            val clip = DocsClip("terminal-sessions")
            runMosaicTest(MosaicSnapshots) {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(80).height(18)) {
                        Row {
                            SessionSidebar(
                                SessionSidebarSide.Left, SidebarContent.TerminalSessions,
                                model, rememberTuiDropdownState(), 28, 18, {}, {},
                                onOpenShellSessionMenu = { menu = it; hover = null },
                                onShellSessionHoverChanged = { request, visible -> hover = request.takeIf { visible } },
                            )
                            AgentRuntimeScreen(
                                model, 52, 18, NewLineKey.ShiftEnter,
                                RuntimeConfigurationDropdowns.remember(model),
                                RuntimeConfigurationDropdowns.remember("suggestions"),
                                { _, _, _, _, _ -> }, {}, {}, {},
                            )
                        }
                        ShellSessionHoverPopup(hover, 52, 18, {}, { hover = null })
                        ShellSessionContextMenu(menu, { menu = null })
                    }
                }
                clip.add(settle(), "printf")
                sendMouseEvent(MouseEvent(5, 1, MouseEvent.Type.Motion))
                settle()
                clip.add(settle(), "sleep 30")
                sendMouseEvent(MouseEvent(5, 1, MouseEvent.Type.Press, MouseEvent.Button.Right))
                settle()
                sendMouseEvent(MouseEvent(5, 1, MouseEvent.Type.Release, MouseEvent.Button.Right))
                clip.add(settle(), "Close session")
                clickLabel("Close session")
                clip.add(settle(), "Terminal sessions")
                assertTrue(shell.completed.value)
            }
            clip.save()
        } finally {
            fixture.close()
        }
    }

    test("overflowing tabs and a nonselected tab context menu") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val tabs = (1..8).map { fixture.newSession("Review $it") }
            var selected by mutableStateOf<SessionViewModel>(tabs.first())
            var target by mutableStateOf<SessionViewModel?>(null)
            var anchor by mutableStateOf<TuiPopupAnchor?>(null)
            var position by mutableStateOf<IntOffset?>(null)
            val clip = DocsClip("session-tabs")
            runMosaicTest(MosaicSnapshots) {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(80).height(18)) {
                        Column(Modifier.width(80).height(18)) {
                            SessionTabBar(
                                collectSessionTabRenderStates(tabs, tabs.indexOf(selected)),
                                mutableStateOf(""), 80,
                                { selected = it },
                                { session, _, a, p -> target = session; anchor = a; position = p },
                                {}, {},
                            )
                            NewSessionScreen(
                                selected as io.github.stream29.kodex.app.session.contract.NewSessionViewModel,
                                80, 17, NewLineKey.ShiftEnter,
                                RuntimeConfigurationDropdowns.remember(selected), {}, {}, {},
                            )
                        }
                        anchor?.let {
                            SessionTabContextMenuPopup(
                                target = requireNotNull(target), anchor = it, clickPosition = position,
                                onDismiss = { anchor = null },
                                onRename = {}, onClose = { anchor = null },
                                onCloseAndArchive = {}, onDelete = {},
                            )
                        }
                    }
                }
                clip.add(settle(), "Review 1")
                sendMouseEvent(MouseEvent(25, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
                settle()
                sendMouseEvent(MouseEvent(25, 0, MouseEvent.Type.Release, MouseEvent.Button.Right))
                clip.add(settle(), "Close")
                assertEquals(tabs.first(), selected)
                assertTrue(target != null && target != selected)
                sendKeyEvent(com.jakewharton.mosaic.terminal.KeyboardEvent(codepoint = 27))
                settle()
                repeat(10) {
                    sendMouseEvent(MouseEvent(50, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
                }
                clip.add(settle(), "Review 8")
                clickLabel("Review 8")
                clip.add(settle(), "Review 8")
                assertEquals(tabs.last(), selected)
            }
            clip.save()
        } finally {
            fixture.close()
        }
    }
}
