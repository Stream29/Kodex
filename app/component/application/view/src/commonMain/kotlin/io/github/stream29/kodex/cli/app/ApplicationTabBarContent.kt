package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun collectSessionTabRenderStates(
    tabs: List<PersistedSessionViewModel>, selectedIndex: Int?,
): List<SessionTabRenderState> = buildList(tabs.size) {
    tabs.forEachIndexed { index, target ->
        key(ApplicationChildKey(target)) {
            val name = target.name.collectAsState().value
            val agent = target.rootAgent.collectAsState().value
            val running = agent?.running?.collectAsState()?.value ?: false
            add(SessionTabRenderState(target, index == selectedIndex, name, running, agent))
        }
    }
}

public actual fun <T : Any> retainApplicationHistoryWidgetStates(
    states: MutableList<Pair<AgentHistoryViewModel, T>>, histories: List<AgentHistoryViewModel>,
) {
    states.removeAll { (previous, _) -> histories.none { current -> current === previous } }
}

public actual fun <T : Any> applicationHistoryWidgetStateFor(
    states: MutableList<Pair<AgentHistoryViewModel, T>>, history: AgentHistoryViewModel, create: () -> T,
): T = states.firstOrNull { (owner, _) -> owner === history }?.second
    ?: create().also { states += history to it }

@Composable
public actual fun collectApplicationTabBar(navigation: ApplicationNavigationState): ApplicationTabBarProjection {
    val selectedIndex = (navigation.selection as? ApplicationTabSelection.SelectedTab)?.index
    val tabs = collectSessionTabRenderStates(navigation.tabs, selectedIndex)
    val identities = remember { SessionTabKeys<PersistedSessionViewModel>() }.identify(navigation.tabs)
    val presentations = tabs.mapIndexed { index, tab ->
        SessionTabPresentation(identities[index].second, tab.sessionName, tab.running)
    }
    val state = SessionTabBarState(presentations, selectedIndex?.let { presentations[it].identity })
    return ApplicationTabProjection(tabs, state, identities)
}

private class ApplicationTabProjection(
    override val tabs: List<SessionTabRenderState>,
    override val state: SessionTabBarState,
    private val identities: List<Pair<PersistedSessionViewModel, SessionTabIdentity>>,
) : ApplicationTabBarProjection {
    override fun targetFor(identity: SessionTabIdentity): PersistedSessionViewModel? =
        identities.firstOrNull { it.second == identity }?.first
}

/** Keeps only renderer keys; reference equality also applies to value-equal child implementations. */
internal class SessionTabKeys<T : Any> {
    private val identities = mutableListOf<Pair<T, SessionTabIdentity>>()
    private var serial = 0

    fun identify(open: List<T>): List<Pair<T, SessionTabIdentity>> {
        identities.removeAll { (previous, _) -> open.none { it === previous } }
        return open.map { target ->
            identities.firstOrNull { it.first === target }
                ?: (target to SessionTabIdentity("tab-${++serial}")).also { identities += it }
        }
    }
}

@Composable
public actual fun ApplicationTabBarContent(
    viewModel: ApplicationViewModel,
    navigation: ApplicationNavigationState,
    projection: ApplicationTabBarProjection,
    renderer: SessionTabBarRenderer,
    onOperationFailure: (Throwable) -> Unit,
    onOpenTabMenu: (ApplicationTabMenuTarget) -> Unit,
    onBeforeNewOrCatalog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentNavigation by rememberUpdatedState(navigation)
    val currentProjection by rememberUpdatedState(projection)
    val report by rememberUpdatedState(onOperationFailure)
    val before by rememberUpdatedState(onBeforeNewOrCatalog)
    val openMenu by rememberUpdatedState(onOpenTabMenu)
    fun targetFor(identity: SessionTabIdentity): PersistedSessionViewModel? =
        currentProjection.targetFor(identity)?.takeIf { target -> currentNavigation.tabs.any { it === target } }
    fun command(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { report(failure) }
        }
    }
    SessionTabBarContent(
        projection.state,
        object : SessionTabBarCallbacks {
            override fun select(identity: SessionTabIdentity) {
                val target = targetFor(identity) ?: return
                command { viewModel.selectTab(target) }
            }
            override fun close(identity: SessionTabIdentity) {
                val target = targetFor(identity) ?: return
                command { viewModel.closeTab(target) }
            }
            override fun openContextMenu(identity: SessionTabIdentity) {
                // The admitted geometry callback below opens the host's exact target.
            }
            override fun selectNewSession() { before(); command { viewModel.selectNewSession() } }
            override fun openSessions() { before(); command { viewModel.openSessionCatalogPopup() } }
        },
        renderer,
        onOpenTabMenu = { request ->
            val target = targetFor(request.identity)
            if (target != null) openMenu(ApplicationTabMenuTarget(target, request.label, request.placement))
        },
    )
}
