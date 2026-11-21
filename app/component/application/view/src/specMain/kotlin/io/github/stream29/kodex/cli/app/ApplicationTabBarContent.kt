package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.*

/** Read-only observations of one exact tab child, not another registry or execution owner. */
public data class SessionTabRenderState(
    public val target: AgentTabViewModel,
    public val selected: Boolean,
    public val sessionName: String,
    public val running: Boolean = false,
    public val agent: AgentViewModel? = null,
)

/** Prunes widget state only, by exact currently owned History child; never closes a child. */
public expect fun <T : Any> retainApplicationHistoryWidgetStates(
    states: MutableList<Pair<AgentHistoryViewModel, T>>, histories: List<AgentHistoryViewModel>,
): Unit

/** Returns one renderer-owned state per exact History child, not per storage/session identifier. */
public expect fun <T : Any> applicationHistoryWidgetStateFor(
    states: MutableList<Pair<AgentHistoryViewModel, T>>, history: AgentHistoryViewModel, create: () -> T,
): T

/** Frozen frame projection. Departed children no longer resolve in a new projection. */
public interface ApplicationTabBarProjection {
    public val tabs: List<SessionTabRenderState>
    public val state: SessionTabBarState
    public fun targetFor(identity: SessionTabIdentity): AgentTabViewModel?
}

/** Retains renderer keys by exact child; an observed departure retires its key before readmission. */
@Composable
public expect fun collectApplicationTabBar(navigation: ApplicationNavigationState): ApplicationTabBarProjection

/** Object identity denotes one fresh menu opening, not a value-equal route or retained Session. */
public class ApplicationTabMenuTarget(
    public val target: AgentTabViewModel,
    public val label: String,
    public val placement: SessionTabMenuPlacement,
)

/**
 * Adapts component identities to exact still-admitted Session instances. Application remains the
 * command owner and revalidates after suspension. No automatic retries or selected-index lookup.
 * Ordinary command failures use [onOperationFailure]; cancellation is not reported.
 */
@Composable
public expect fun ApplicationTabBarContent(
    viewModel: ApplicationViewModel,
    navigation: ApplicationNavigationState,
    projection: ApplicationTabBarProjection,
    renderer: SessionTabBarRenderer,
    onOperationFailure: (Throwable) -> Unit,
    onOpenTabMenu: (ApplicationTabMenuTarget) -> Unit,
    onBeforeNewOrCatalog: () -> Unit = {},
    showSessions: Boolean = false,
): Unit
