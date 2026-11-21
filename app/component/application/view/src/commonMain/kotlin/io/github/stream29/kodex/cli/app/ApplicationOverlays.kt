package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.TerminalOutputReader
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
public actual fun rememberApplicationOverlays(
    selectedAgent: AgentViewModel?, left: SidebarContent, right: SidebarContent, popup: ApplicationPopupState,
): ApplicationOverlayState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { ApplicationOverlayStateImpl(scope) }
    LaunchedEffect(ApplicationChildKey(selectedAgent)) { state.clearSidebar() }
    LaunchedEffect(left) { state.clearSide(SessionSidebarSide.Left) }
    LaunchedEffect(right) { state.clearSide(SessionSidebarSide.Right) }
    LaunchedEffect(popup) {
        if (popup != ApplicationPopupState.Closed) state.clear()
    }
    return state
}

internal class ApplicationOverlayStateImpl(private val scope: CoroutineScope) : ApplicationOverlayState {
    override var menu by mutableStateOf<ApplicationOverlayMenu?>(null)
        private set
    override var hover by mutableStateOf<ApplicationOverlayHover?>(null)
        private set
    private var popupHovered = false
    private var closeJob: Job? = null

    override fun openMenu(opening: ApplicationOverlayMenu) {
        clearHover()
        menu = opening
    }
    override fun openShellOutput(
        expected: ApplicationOverlayMenu.Shell, agent: AgentViewModel, placement: SidebarShellOutputPlacement,
    ): Boolean {
        if (menu !== expected || !expected.isAvailableFrom(agent)) return false
        openMenu(ApplicationOverlayMenu.ShellOutput(expected, agent, placement))
        return true
    }

    /** Returns false when the original entry is gone (no menu to restore). */
    override fun backFromShellOutput(expected: ApplicationOverlayMenu.ShellOutput): Boolean {
        if (menu !== expected) return false
        val original = expected.original
        if (!original.isAvailableFrom(expected.agent)) {
            dismissMenu(expected)
            return false
        }
        openMenu(ApplicationOverlayMenu.Shell(original.side, SidebarShellMenuOpening(original.opening.interaction)))
        return true
    }
    override fun hoverChanged(candidate: ApplicationOverlayHover, entered: Boolean) {
        if (entered) {
            if (menu != null) return
            closeJob?.cancel()
            popupHovered = false
            if (!sameRow(hover, candidate)) hover = candidate
        } else if (sameRow(hover, candidate)) {
            hover?.let(::closeAfterGrace)
        }
    }
    override fun popupHoverChanged(expected: ApplicationOverlayHover, entered: Boolean) {
        if (hover !== expected) return
        popupHovered = entered
        closeJob?.cancel()
        if (!entered) closeAfterGrace(expected)
    }
    override fun dismissMenu(expected: ApplicationOverlayMenu): Boolean {
        if (menu !== expected) return false
        menu = null
        return true
    }
    override fun dismissHover(expected: ApplicationOverlayHover): Boolean {
        if (hover !== expected) return false
        clearHover()
        return true
    }
    override fun clearSide(side: SessionSidebarSide) {
        if ((menu as? ApplicationOverlayMenu.Sidebar)?.side == side) menu = null
        if (hover?.side == side) clearHover()
    }
    override fun clearSidebar() {
        if (menu is ApplicationOverlayMenu.Sidebar) menu = null
        clearHover()
    }
    override fun clear() {
        menu = null
        clearHover()
    }
    private fun clearHover() {
        closeJob?.cancel()
        closeJob = null
        hover = null
        popupHovered = false
    }
    private fun closeAfterGrace(expected: ApplicationOverlayHover) {
        closeJob?.cancel()
        closeJob = scope.launch {
            delay(180.milliseconds)
            if (hover === expected && !popupHovered) clearHover()
        }
    }
}

private fun ApplicationOverlayMenu.Shell.isAvailableFrom(agent: AgentViewModel): Boolean {
    val interaction = opening.interaction
    val session = interaction.session
    return interaction.isPlaced && !session.completed.value &&
        agent.lifecycle.value == AgentLifecycleState.Open &&
        agent.shellSessions.activeSessions.value[session.sessionId] === session
}

private fun sameRow(first: ApplicationOverlayHover?, second: ApplicationOverlayHover): Boolean =
    first != null && first.side == second.side && first.rowIdentity === second.rowIdentity &&
        (first is ApplicationOverlayHover.Shell && second is ApplicationOverlayHover.Shell ||
            first is ApplicationOverlayHover.Index && second is ApplicationOverlayHover.Index)

@Composable
public actual fun ApplicationSidebarOverlaysContent(
    state: ApplicationOverlayState, selectedAgent: AgentViewModel?,
    shellRenderer: SidebarShellRenderer, indexRenderer: HistoryIndexRenderer,
    isSideVisible: (SessionSidebarSide) -> Boolean,
    onShellOutputFallback: () -> Unit,
    application: ApplicationViewModel?,
    focusEditedComposer: (AgentViewModel) -> Unit,
    onOperationFailure: (Throwable) -> Unit,
) {
    val currentAgent by rememberUpdatedState(selectedAgent)
    val currentVisibility by rememberUpdatedState(isSideVisible)
    val focus by rememberUpdatedState(focusEditedComposer)
    val report by rememberUpdatedState(onOperationFailure)
    val scope = rememberCoroutineScope()
    when (val hover = state.hover) {
        is ApplicationOverlayHover.Shell -> {
            val registry = selectedAgent?.shellSessions
            val sessions = registry?.activeSessions?.collectAsState()?.value
            val lifecycle = selectedAgent?.lifecycle?.collectAsState()?.value
            val session = hover.interaction.session
            val valid = sessions?.get(session.sessionId) === session && hover.interaction.isPlaced &&
                isSideVisible(hover.side) && lifecycle == AgentLifecycleState.Open
            LaunchedEffect(hover, valid) { if (!valid) state.dismissHover(hover) }
            if (valid) SidebarShellHoverContent(
                hover.interaction, registry, shellRenderer,
                { state.popupHoverChanged(hover, it) }, { state.dismissHover(hover) },
            )
        }
        is ApplicationOverlayHover.Index -> {
            val valid = hover.interaction.target.viewModel === selectedAgent?.historyIndex && hover.interaction.isPlaced
            LaunchedEffect(hover, valid) { if (!valid) state.dismissHover(hover) }
            if (valid) HistoryIndexHoverContent(
                hover.interaction, indexRenderer,
                { state.popupHoverChanged(hover, it) }, { state.dismissHover(hover) },
            )
        }
        null -> Unit
    }
    when (val menu = state.menu) {
        is ApplicationOverlayMenu.Shell -> {
            val sessions = selectedAgent?.shellSessions?.activeSessions?.collectAsState()?.value
            val shell = menu.opening.interaction.session
            val valid = sessions?.get(shell.sessionId) === shell && menu.opening.interaction.isPlaced &&
                isSideVisible(menu.side)
            LaunchedEffect(menu, valid) { if (!valid) state.dismissMenu(menu) }
            if (valid) SidebarShellMenuContent(
                menu.opening, shellRenderer,
                onViewOutput = {
                    val agent = currentAgent
                    if (agent != null) shellRenderer.captureShellOutputPlacement(menu.opening)?.let {
                        state.openShellOutput(menu, agent, it)
                    }
                },
                canAct = {
                    val agent = currentAgent
                    state.menu === menu && currentVisibility(menu.side) && agent != null && menu.isAvailableFrom(agent)
                },
            ) { state.dismissMenu(menu) }
        }
        is ApplicationOverlayMenu.ShellOutput -> {
            val lifecycle by menu.agent.lifecycle.collectAsState()
            val valid = menu.agent === selectedAgent && isSideVisible(menu.side) && lifecycle == AgentLifecycleState.Open
            LaunchedEffect(menu, valid) { if (!valid) state.dismissMenu(menu) }
            if (valid) key(menu) {
                val reader = remember(menu) { TerminalOutputReader(menu.original.opening.interaction.session, menu.agent.shellSessions) }
                LaunchedEffect(reader) { reader.observe() }
                shellRenderer.ShellOutput(menu, reader.state) {
                    if (state.menu === menu && currentAgent === menu.agent && currentVisibility(menu.side)) {
                        if (!state.backFromShellOutput(menu)) onShellOutputFallback()
                    }
                }
            }
        }
        is ApplicationOverlayMenu.Index -> {
            val agent = selectedAgent
            val tab = application?.navigation?.collectAsState()?.value?.selectedTab
            val origin = menu.opening.interaction
            val valid = origin.target.viewModel === agent?.historyIndex && origin.isPlaced && isSideVisible(menu.side)
            LaunchedEffect(menu, valid) { if (!valid) state.dismissMenu(menu) }
            if (valid) {
                val canEdit = if (tab == null) false else {
                    val running by agent.running.collectAsState()
                    val runtime by agent.state.collectAsState()
                    runtime.canEditHistory(running)
                }
                fun isCurrent(): Boolean = currentAgent === agent && currentVisibility(menu.side) && origin.isPlaced &&
                    origin.target.isCurrent() &&
                    (application == null || tab != null && application.isSelectedHistoryAgent(tab, agent))
                HistoryIndexMenuContent(
                    menu.opening, indexRenderer,
                    canEdit = canEdit,
                    admit = { state.menu === menu && isCurrent() },
                    edit = { command, target, text ->
                        if (application != null && tab != null && isCurrent() &&
                            agent.canEditBoundHistory() && state.dismissMenu(menu)
                        ) scope.launch {
                            if (!isCurrent() || !agent.canEditBoundHistory()) return@launch
                            try { application.editHistory(tab, agent, target.index, target.generation, command, text, focus) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Throwable) { report(failure) }
                        }
                    },
                ) { state.dismissMenu(menu) }
            }
        }
        else -> Unit
    }
}
