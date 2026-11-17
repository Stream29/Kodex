@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.RenameSessionPopupViewModel
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenApplicationOverlaysTest by testSuite {
    test("root admits exact Shell and Index children and stale dismiss cannot close a replacement menu") { offscreenTest {
        val firstShell = SidebarTestShell(7, "first")
        val secondShell = SidebarTestShell(7, "second")
        val firstIndex = IndexRenderFixture(listOf(3))
        val secondIndex = IndexRenderFixture(listOf(3))
        val first = SidebarTestAgent(MutableStateFlow(mapOf<Int, AgentShellSession>(7 to firstShell)), firstIndex)
        val second = SidebarTestAgent(MutableStateFlow(mapOf<Int, AgentShellSession>(7 to secondShell)), secondIndex)
        var selected by mutableStateOf<AgentViewModel>(first)
        var state: ApplicationOverlayState? = null
        val scene = OffscreenScene.create {
            RootSidebarHost(selected, SidebarContent.TerminalSessions, ApplicationPopupState.Closed) { state = it }
        }
        try {
            scene.settle { scene.hasDescription("Shell session 7") && scene.hasDescription("History index 3") }
            scene.contextDescription("Shell session 7")
            scene.settle { scene.hasText("Close session") }
            val overlays = assertNotNull(state)
            val oldShellMenu = assertIs<ApplicationOverlayMenu.Shell>(overlays.menu)
            assertSame(firstShell, oldShellMenu.opening.interaction.session)
            scene.escape()
            scene.settle { overlays.menu == null }
            scene.contextDescription("History index 3")
            scene.settle { scene.hasText("Check out") && !scene.hasText("Close session") }
            val indexMenu = assertIs<ApplicationOverlayMenu.Index>(overlays.menu)
            assertSame(firstIndex, indexMenu.opening.interaction.target.viewModel)
            assertFalse(overlays.dismissMenu(oldShellMenu))
            scene.click("Check out")
            scene.settle { overlays.menu == null && firstIndex.scrolled.size == 1 }
            assertEquals(listOf(7L to 3), firstIndex.scrolled)
            scene.contextDescription("Shell session 7")
            scene.settle { scene.hasText("Close session") }
            selected = second
            scene.settle { overlays.menu == null && !scene.hasText("Close session") }
            assertEquals(0, firstShell.closeCount + secondShell.closeCount)
            scene.contextDescription("Shell session 7")
            scene.settle { scene.hasText("Close session") }
            val replacement = assertIs<ApplicationOverlayMenu.Shell>(overlays.menu)
            assertSame(secondShell, replacement.opening.interaction.session)
            assertFalse(overlays.dismissMenu(oldShellMenu))
            scene.click("Close session")
            scene.settle { overlays.menu == null && secondShell.closeCount == 1 }
            assertEquals(0, firstShell.closeCount)
            assertTrue(secondIndex.scrolled.isEmpty())
            scene.frame()
        } finally { scene.close(); firstIndex.close(); secondIndex.close() }
        assertEquals(0, first.closeCount + second.closeCount)
    } }
    test("root content and Application popup changes retire hover without disposing borrowed processes") { offscreenTest {
        val shell = SidebarTestShell(7, "echo full hover")
        val index = IndexRenderFixture(listOf(3))
        val agent = SidebarTestAgent(MutableStateFlow(mapOf<Int, AgentShellSession>(7 to shell)), index)
        val session = NavigationSession(31, "popup owner", agent)
        val rename = createSessionRenameViewModel("popup owner") { error("No rename operation in overlay context fixture") }
        val dialog = ApplicationPopupState.RenameSession(object : RenameSessionPopupViewModel, SessionRenameViewModel by rename {
            override val target = session
        })
        var content by mutableStateOf(SidebarContent.TerminalSessions)
        var popup by mutableStateOf<ApplicationPopupState>(ApplicationPopupState.Closed)
        var state: ApplicationOverlayState? = null
        val scene = OffscreenScene.create { RootSidebarHost(agent, content, popup) { state = it } }
        try {
            scene.settle { scene.hasDescription("Shell session 7") }
            scene.moveOver("Shell session 7")
            scene.settle { scene.hasText("Session 7") }
            val overlays = assertNotNull(state)
            val oldHover = assertNotNull(overlays.hover)
            content = SidebarContent.None
            scene.settle { overlays.hover == null && !scene.hasText("Session 7") }
            content = SidebarContent.TerminalSessions
            scene.settle { scene.hasDescription("Shell session 7") }
            scene.moveOver("History index 3")
            scene.moveOver("Shell session 7")
            scene.settle { scene.hasText("Session 7") }
            val replacement = assertNotNull(overlays.hover)
            assertNotSame(oldHover, replacement)
            assertFalse(overlays.dismissHover(oldHover))
            assertSame(replacement, overlays.hover)
            popup = dialog
            scene.settle { overlays.hover == null && overlays.menu == null && !scene.hasText("Session 7") }
            assertEquals(0, shell.closeCount + agent.closeCount + session.closeCount)
            scene.frame()
        } finally { scene.close(); rename.close(); index.close() }
    } }
}

/** Real sidebar/menu/hover renderer, without an Application dialog or DOM editor host. */
@Composable
private fun RootSidebarHost(
    selected: AgentViewModel, left: SidebarContent, popup: ApplicationPopupState,
    observe: (ApplicationOverlayState) -> Unit,
) {
    val state = rememberApplicationOverlays(selected, left, SidebarContent.HistoryIndex, popup)
    observe(state)
    @Composable fun renderer(side: SessionSidebarSide) = rememberComposeSessionSidebarRenderer(
        onShellHoverChanged = { origin, entered -> state.hoverChanged(ApplicationOverlayHover.Shell(side, origin), entered) },
        onOpenShellMenu = { state.openMenu(ApplicationOverlayMenu.Shell(side, it)) },
        onIndexHoverChanged = { origin, entered -> state.hoverChanged(ApplicationOverlayHover.Index(side, origin), entered) },
        onOpenIndexMenu = { state.openMenu(ApplicationOverlayMenu.Index(side, it)) },
    )
    val shell = renderer(SessionSidebarSide.Left)
    val right = renderer(SessionSidebarSide.Right)
    Row(Modifier.fillMaxWidth().height(280.dp)) {
        Box(Modifier.width(340.dp)) { SessionSidebarContent(SessionSidebarSide.Left, left, selected, shell, {}) }
        Box(Modifier.width(340.dp)) { SessionSidebarContent(SessionSidebarSide.Right, SidebarContent.HistoryIndex, selected, right, {}) }
    }
    ApplicationSidebarOverlaysContent(state, selected, shell, rememberComposeHistoryIndexRenderer())
}

private fun OffscreenScene.moveOver(description: String) {
    val node = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }
    scene.sendPointerEvent(PointerEventType.Move, node.boundsInWindow.center)
}
