package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel

/** Read-only observations frozen for one root composition; no registry, execution or writable state. */
public interface ApplicationFrame {
    public val navigation: ApplicationNavigationState
    public val popup: ApplicationPopupState
    public val tabs: ApplicationTabBarProjection
    public val selectedAgent: AgentViewModel?
    public val selectedLifecycle: PersistedSessionLifecycleState?
}

/** Observes each tab's Agent once and keys selected lifecycle observation by exact child. */
@Composable
public expect fun collectApplicationFrame(viewModel: ApplicationViewModel): ApplicationFrame

/** Borrowed Agent surface or the existing loading/failure/closed message; never creates a Session. */
@Composable
public expect fun ApplicationPersistedSurfaceContent(
    frame: ApplicationFrame,
    agent: @Composable (AgentViewModel) -> Unit,
    status: @Composable (String) -> Unit,
): Unit

/** Captured root targets; callers never replace them with the selected tab after suspension. */
public interface ApplicationRootActions {
    public fun browseWorkingDirectory(owner: AgentSettingsViewModel): Unit
    public fun browseSuggestedWorkingDirectory(owner: AgentViewModel, callId: String): Unit
    /** Opens Current Session for a captured target, or New Session for the singleton draft. */
    public fun openSettings(target: PersistedSessionViewModel?): Unit
}

/** Waits belong to the root mount. Ordinary failures use its existing outlet; no cancellation report or retry. */
@Composable
public expect fun rememberApplicationRootActions(
    viewModel: ApplicationViewModel, onOperationFailure: (Throwable) -> Unit,
): ApplicationRootActions
