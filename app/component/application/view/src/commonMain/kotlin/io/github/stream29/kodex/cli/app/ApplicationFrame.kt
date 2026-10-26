package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun collectApplicationFrame(viewModel: ApplicationViewModel): ApplicationFrame =
    key(ApplicationChildKey(viewModel)) {
        val navigation = viewModel.navigation.collectAsState().value
        val tabs = collectApplicationTabBar(navigation)
        val popup = viewModel.popup.collectAsState().value
        val selected = navigation.selectedSession
        val lifecycle = selected?.let { key(ApplicationChildKey(it)) { it.lifecycle.collectAsState().value } }
        RootFrame(navigation, popup, tabs, tabs.tabs.firstOrNull { it.target === selected }?.agent, lifecycle)
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
            override fun browseSuggestedWorkingDirectory(owner: AgentViewModel, callId: String) = command {
                viewModel.openSuggestedWorkingDirectoryPopup(owner, callId)
            }
            override fun openSettings(target: PersistedSessionViewModel?) = command {
                viewModel.openSettingsPopup(target, if (target == null) SettingsPage.NewSession else SettingsPage.CurrentSession)
            }
        }
    }
}

internal class ApplicationChildKey(private val child: Any?) {
    override fun equals(other: Any?): Boolean = other is ApplicationChildKey && other.child === child
    override fun hashCode(): Int = 0
}
