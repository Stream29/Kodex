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
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.RenameSessionPopupViewModel
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuAction
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuOpening
import io.github.stream29.kodex.cli.historyindex.HistoryIndexRenderer
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenApplicationOverlaysTest by testSuite {
    test("Index mutating actions use the selected binding and recheck a retained callback before admission") { offscreenTest {
        val firstIndex = IndexRenderFixture(listOf(3))
        val otherIndex = IndexRenderFixture(listOf(3))
        firstIndex.window.value = firstIndex.window.value.copy(entries = firstIndex.window.value.entries.map {
            it.copy(editableUserText = "editable")
        })
        val requests = mutableListOf<Pair<Int, Long>>()
        val first = object : SidebarTestAgent(historyIndex = firstIndex) {
            override val state = MutableStateFlow<AgentStateValue>(AgentStateValue.Empty)
            override val running = MutableStateFlow(false)
            override suspend fun forkHistory(untilExclusive: Int, expectedGeneration: Long): Int {
                requests += untilExclusive to expectedGeneration
                return 81
            }
        }
        val other = object : SidebarTestAgent(historyIndex = otherIndex) {
            override val state = MutableStateFlow<AgentStateValue>(AgentStateValue.Empty)
            override val running = MutableStateFlow(false)
        }
        val firstTab = NavigationSession(13, "source", first)
        val otherTab = NavigationSession(25, "other", other)
        val opened = mutableListOf<Int>()
        val app = object : NavigationApplication(listOf(firstTab, otherTab)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
            override suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel {
                opened += sessionIndex
                return firstTab
            }
        }
        app.selectTab(firstTab)
        var actions: List<HistoryIndexMenuAction> = emptyList()
        val scene = OffscreenScene.create {
            val navigation by app.navigation.collectAsState()
            val agent = assertNotNull(navigation.selectedTab?.agent?.value)
            RootSidebarHost(
                agent, SidebarContent.TerminalSessions, ApplicationPopupState.Closed,
                application = app,
                readActions = { actions = it },
            )
        }
        try {
            scene.settle { scene.hasDescription("History index 3") }
            scene.contextDescription("History index 3")
            scene.settle { scene.hasText("Fork from here") && scene.hasText("Revert and edit") }
            val runningStaleFork = actions.single { it.label == "Fork from here" }.onClick
            first.running.value = true
            runningStaleFork() // Before recomposition, admission must consult the actual Agent state.
            scene.settle { scene.hasText("Go to") && !scene.hasText("Fork from here") }
            assertTrue(requests.isEmpty())
            first.running.value = false
            scene.settle { scene.hasText("Fork from here") }
            val selectionStaleFork = actions.single { it.label == "Fork from here" }.onClick
            app.selectTab(otherTab)
            selectionStaleFork()
            scene.settle { !scene.hasText("Go to") }
            assertTrue(requests.isEmpty())
            app.selectTab(firstTab)
            scene.settle { scene.hasDescription("History index 3") }
            scene.contextDescription("History index 3")
            scene.settle { scene.hasText("Fork from here") }
            scene.click("Fork from here")
            scene.settle { opened == listOf(81) }
            assertEquals(listOf(4 to 7L), requests)
            assertEquals(ApplicationTabSelection.SelectedTab(0), app.navigation.value.selection)
        } finally { scene.close(); firstIndex.close(); otherIndex.close() }
    } }
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
            scene.settle { scene.hasText("Go to") && !scene.hasText("Close session") }
            val indexMenu = assertIs<ApplicationOverlayMenu.Index>(overlays.menu)
            assertSame(firstIndex, indexMenu.opening.interaction.target.viewModel)
            assertFalse(overlays.dismissMenu(oldShellMenu))
            scene.click("Go to")
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
        val shell = SidebarTestShell(7, "echo full hover", "Inspect hover fixture")
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
            scene.settle { scene.hasText("Terminal #7") && scene.hasText(shell.arguments.command) }
            assertTrue(scene.hasText("Inspect hover fixture"))
            val overlays = assertNotNull(state)
            val oldHover = assertNotNull(overlays.hover)
            content = SidebarContent.None
            scene.settle { overlays.hover == null && !scene.hasText(shell.arguments.command) }
            content = SidebarContent.TerminalSessions
            scene.settle { scene.hasDescription("Shell session 7") }
            scene.moveOver("History index 3")
            scene.moveOver("Shell session 7")
            scene.settle { scene.hasText("Terminal #7") && scene.hasText(shell.arguments.command) }
            val replacement = assertNotNull(overlays.hover)
            assertNotSame(oldHover, replacement)
            assertFalse(overlays.dismissHover(oldHover))
            assertSame(replacement, overlays.hover)
            popup = dialog
            scene.settle { overlays.hover == null && overlays.menu == null && !scene.hasText(shell.arguments.command) }
            assertEquals(0, shell.closeCount + agent.closeCount + session.closeCount)
            scene.frame()
        } finally { scene.close(); rename.close(); index.close() }
    } }
}

/** Real sidebar/menu/hover renderer, without an Application dialog or DOM editor host. */
@Composable
private fun RootSidebarHost(
    selected: AgentViewModel, left: SidebarContent, popup: ApplicationPopupState,
    application: ApplicationViewModel? = null,
    readActions: ((List<HistoryIndexMenuAction>) -> Unit)? = null,
    observe: (ApplicationOverlayState) -> Unit = {},
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
    val index = rememberComposeHistoryIndexRenderer()
    val capturing = if (readActions == null) index else object : HistoryIndexRenderer by index {
        @Composable override fun Menu(
            opening: HistoryIndexMenuOpening, timestamp: String?, actions: List<HistoryIndexMenuAction>,
            onDismiss: () -> Unit,
        ) {
            readActions(actions)
            index.Menu(opening, timestamp, actions, onDismiss)
        }
    }
    ApplicationSidebarOverlaysContent(state, selected, shell, capturing, application = application)
}

private fun OffscreenScene.moveOver(description: String) {
    val node = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }
    scene.sendPointerEvent(PointerEventType.Move, node.boundsInWindow.center)
}
