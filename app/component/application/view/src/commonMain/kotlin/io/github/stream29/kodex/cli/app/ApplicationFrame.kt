package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.cli.historyindex.HistoryIndexNavigation
import io.github.stream29.kodex.cli.historyindex.HistoryIndexParentAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun collectApplicationFrame(viewModel: ApplicationViewModel): ApplicationFrame =
    key(ApplicationChildKey(viewModel)) {
        val navigation = viewModel.navigation.collectAsState().value
        val tabs = collectApplicationTabBar(navigation)
        val popup = viewModel.popup.collectAsState().value
        val selected = navigation.selectedSession
        val surface = selected?.let { key(ApplicationChildKey(it)) {
            val selectedAgent = it.selectedAgent
            val agent = if (selectedAgent === it.rootAgent) tabs.tabs.firstOrNull { tab -> tab.target === it }?.agent
                else selectedAgent.collectAsState().value
            agent to it.lifecycle.collectAsState().value
        } }
        RootFrame(navigation, popup, tabs, surface?.first, surface?.second)
    }

private class RootFrame(
    override val navigation: ApplicationNavigationState,
    override val popup: ApplicationPopupState,
    override val tabs: ApplicationTabBarProjection,
    override val selectedAgent: AgentViewModel?,
    override val selectedLifecycle: PersistedSessionLifecycleState?,
) : ApplicationFrame

@Composable
public actual fun ApplicationPersistedSurfaceContent(
    frame: ApplicationFrame, agent: @Composable (AgentViewModel) -> Unit, status: @Composable (String) -> Unit,
) {
    val selected = frame.navigation.selectedSession ?: return
    key(ApplicationChildKey(selected)) {
        val child = frame.selectedAgent
        if (child != null) key(ApplicationChildKey(child)) { agent(child) }
        else status(when (val lifecycle = frame.selectedLifecycle) {
            is PersistedSessionLifecycleState.Failed -> lifecycle.detail
            PersistedSessionLifecycleState.Closed -> "Session view closed."
            else -> "Loading Session…"
        })
    }
}

@Composable
public actual fun rememberAgentPageActionTarget(
    session: PersistedSessionViewModel, agent: AgentViewModel,
): AgentPageActionTarget {
    val mounted = remember(ApplicationChildKey(session), ApplicationChildKey(agent)) { mutableStateOf(false) }
    DisposableEffect(mounted) {
        mounted.value = true
        onDispose { mounted.value = false }
    }
    return remember(mounted) { AgentPageActionTarget(session, agent) { mounted.value && session.selectedAgent.value === agent } }
}

@Composable
public actual fun rememberApplicationIndexNavigation(
    viewModel: ApplicationViewModel, frame: ApplicationFrame, onOperationFailure: (Throwable) -> Unit,
): HistoryIndexNavigation? {
    val session = frame.navigation.selectedSession ?: return null
    val agent = frame.selectedAgent ?: return null
    val isChild = frame.tabs.tabs.firstOrNull { it.target === session }?.agent !== agent
    val scope = rememberCoroutineScope()
    val report = rememberUpdatedState(onOperationFailure)
    return remember(viewModel, ApplicationChildKey(session), ApplicationChildKey(agent), isChild) {
        fun command(block: suspend (() -> Boolean) -> Unit) {
            val selection = viewModel.navigation.value
            if (selection.selectedSession !== session || session.selectedAgent.value !== agent) return
            scope.launch {
                val isTabSelected = { viewModel.navigation.value === selection }
                try { block(isTabSelected) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { if (isTabSelected()) report.value(failure) }
            }
        }
        HistoryIndexNavigation(
            agent.historyIndex,
            enterChild = { target, child ->
                if (target.viewModel === agent.historyIndex) command { selected ->
                    session.enterChild(agent, target.index, target.generation, target.revision, child, selected)
                }
            },
            returnToParent = if (isChild) { anchor ->
                command { selected ->
                    val type = when (anchor) {
                        HistoryIndexParentAnchor.Creation -> AgentParentAnchor.Creation
                        HistoryIndexParentAnchor.Completion -> AgentParentAnchor.Completion
                    }
                    session.returnToParent(agent, type, selected)
                }
            } else null,
        )
    }
}

@Composable
public actual fun rememberApplicationRootActions(
    viewModel: ApplicationViewModel, onOperationFailure: (Throwable) -> Unit,
): ApplicationRootActions {
    val scope = rememberCoroutineScope()
    val report = rememberUpdatedState(onOperationFailure)
    return remember(viewModel, scope) {
        object : ApplicationRootActions {
            private fun command(block: suspend () -> Unit) {
                scope.launch {
                    try { block() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { report.value(failure) }
                }
            }
            override fun browseWorkingDirectory(owner: AgentSettingsViewModel) = command {
                viewModel.openWorkingDirectoryPopup(owner)
            }
            override fun openSettings(target: PersistedSessionViewModel?) = command {
                viewModel.openSettingsPopup(target, if (target == null) SettingsPage.NewSession else SettingsPage.CurrentSession)
            }
            override fun browseAgentWorkingDirectory(target: AgentPageActionTarget) = command {
                viewModel.openAgentWorkingDirectoryPopup(target)
            }
            override fun openAgentSettings(target: AgentPageActionTarget) = command {
                viewModel.openAgentSettingsPopup(target)
            }
        }
    }
}

internal class ApplicationChildKey(private val child: Any?) {
    override fun equals(other: Any?): Boolean = other is ApplicationChildKey && other.child === child
    override fun hashCode(): Int = 0
}
