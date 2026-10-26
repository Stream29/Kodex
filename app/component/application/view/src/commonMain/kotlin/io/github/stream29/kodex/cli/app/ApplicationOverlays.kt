package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlinx.coroutines.CoroutineScope
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
    LaunchedEffect(ApplicationChildKey(selectedAgent), left, right, popup) {
        state.clearSidebar()
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

private fun sameRow(first: ApplicationOverlayHover?, second: ApplicationOverlayHover): Boolean =
    first != null && first.side == second.side && first.rowIdentity === second.rowIdentity &&
        (first is ApplicationOverlayHover.Shell && second is ApplicationOverlayHover.Shell ||
            first is ApplicationOverlayHover.Index && second is ApplicationOverlayHover.Index)

@Composable
public actual fun ApplicationSidebarOverlaysContent(
    state: ApplicationOverlayState, selectedAgent: AgentViewModel?,
    shellRenderer: SidebarShellRenderer, indexRenderer: HistoryIndexRenderer,
) {
    when (val hover = state.hover) {
        is ApplicationOverlayHover.Shell -> {
            val sessions = selectedAgent?.shellSessions?.activeSessions?.collectAsState()?.value
            val valid = sessions?.values?.any { it === hover.interaction.session } == true && hover.interaction.isPlaced
            LaunchedEffect(hover, valid) { if (!valid) state.dismissHover(hover) }
            if (valid) SidebarShellHoverContent(
                hover.interaction, shellRenderer,
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
            val valid = sessions?.values?.any { it === menu.opening.interaction.session } == true && menu.opening.interaction.isPlaced
            LaunchedEffect(menu, valid) { if (!valid) state.dismissMenu(menu) }
            if (valid) SidebarShellMenuContent(menu.opening, shellRenderer) { state.dismissMenu(menu) }
        }
        is ApplicationOverlayMenu.Index -> {
            val valid = menu.opening.interaction.target.viewModel === selectedAgent?.historyIndex && menu.opening.interaction.isPlaced
            LaunchedEffect(menu, valid) { if (!valid) state.dismissMenu(menu) }
            if (valid) HistoryIndexMenuContent(menu.opening, indexRenderer) { state.dismissMenu(menu) }
        }
        else -> Unit
    }
}
