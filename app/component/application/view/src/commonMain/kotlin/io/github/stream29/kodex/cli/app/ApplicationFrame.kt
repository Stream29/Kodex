package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
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
        val selected = navigation.selectedTab
        val agent = tabs.tabs.firstOrNull { it.target === selected }?.agent
        val lifecycle = selected?.let { key(ApplicationChildKey(it)) { it.lifecycle.collectAsState().value } }
        RootFrame(navigation, popup, tabs, agent, lifecycle)
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
    val selected = frame.navigation.selectedTab ?: return
    key(ApplicationChildKey(selected)) {
        val child = frame.selectedAgent
        if (child != null) key(ApplicationChildKey(child)) { agent(child) }
        else status(when (val lifecycle = frame.selectedLifecycle) {
            is PersistedSessionLifecycleState.Failed -> lifecycle.detail
            PersistedSessionLifecycleState.Closed -> "Agent view closed."
            else -> "Loading Agent…"
        })
    }
}

@Composable
public actual fun rememberAgentPageActionTarget(
    tab: AgentTabViewModel, agent: AgentViewModel,
): AgentPageActionTarget {
    val mounted = remember(ApplicationChildKey(tab), ApplicationChildKey(agent)) { mutableStateOf(false) }
    DisposableEffect(mounted) {
        mounted.value = true
        onDispose { mounted.value = false }
    }
    return remember(mounted) { AgentPageActionTarget(tab, agent) { mounted.value && tab.agent.value === agent } }
}

@Composable
public actual fun rememberApplicationIndexNavigation(
    viewModel: ApplicationViewModel, frame: ApplicationFrame, onOperationFailure: (Throwable) -> Unit,
): HistoryIndexNavigation? {
    val scope = rememberCoroutineScope()
    val tab = frame.navigation.selectedTab ?: return null
    val agent = frame.selectedAgent ?: return null
    val parentId = frame.navigation.parentAgentIds[tab.agentId]
    val parent = frame.tabs.tabs.firstOrNull { it.target.agentId == parentId }
    val parentBinding = parent?.agent
    val completion = if (parent != null && parentBinding != null) key(
        ApplicationChildKey(parent.target), ApplicationChildKey(parentBinding),
        ApplicationChildKey(tab), ApplicationChildKey(agent),
    ) {
        remember { parent.target.observeCompletionAnchor(tab.agentId) }.collectAsState(initial = false)
    } else null
    val report = rememberUpdatedState(onOperationFailure)
    return remember(viewModel, ApplicationChildKey(tab), ApplicationChildKey(agent), parentId,
        ApplicationChildKey(parent?.target), ApplicationChildKey(parentBinding), completion,
    ) {
        fun command(block: suspend (AgentPageActionTarget) -> Unit) {
            val selection = viewModel.navigation.value
            val source = AgentPageActionTarget(tab, agent) { viewModel.navigation.value === selection }
            if (selection.selectedTab !== tab || tab.agent.value !== agent || !source.isCurrent()) return
            scope.launch {
                try { block(source) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { if (source.isCurrent()) report.value(failure) }
            }
        }
        HistoryIndexNavigation(
            agent.historyIndex,
            enterChild = { target, child ->
                if (target.viewModel === agent.historyIndex) command { source ->
                    viewModel.enterChild(source, target.index, target.generation, target.revision, child)
                }
            },
            returnToParent = if (parentId != null) { anchor ->
                command { source ->
                    val type = when (anchor) {
                        HistoryIndexParentAnchor.Creation -> AgentParentAnchor.Creation
                        HistoryIndexParentAnchor.Completion -> AgentParentAnchor.Completion
                    }
                    viewModel.returnToParent(source, type)
                }
            } else null,
            hasCompletionAnchor = {
                val selection = viewModel.navigation.value
                completion?.value == true && selection.selectedTab === tab && tab.agent.value === agent &&
                    selection.parentAgentIds[tab.agentId] == parentId &&
                    parent != null && parent.target.agent.value === parentBinding &&
                    selection.tabs.any { it === parent.target }
            },
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
