package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.models.AgentStateValue

/** Frozen inputs for this frame's measurement, not a retained execution or editable state owner. */
public data class AgentPageMeasureInputs(
    public val composer: ComposerState,
    public val pendingSteer: List<StableIndexEvent.Steerable>,
    public val hostInteractionPending: Boolean,
    public val notice: String?,
    public val state: AgentStateValue,
    public val running: Boolean,
    public val settings: KodexAgentSettings,
    public val tokenCount: Long?,
)

/** Slots keep geometry, focus and native widget handles below the shared page composition. */
public interface AgentPageRenderScope {
    @Composable public fun History(
        viewModel: AgentHistoryViewModel, shellSessions: AgentShellSessionRegistry, canEdit: Boolean,
    )
    @Composable public fun UserInput(viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending)
    @Composable public fun Suggestion(
        viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending,
        onBrowseWorkingDirectory: (String) -> Unit,
    )
    @Composable public fun PendingSteer()
    @Composable public fun Notice(message: String)
    /** [enabled] also controls initial focus, leaving pending-tool focus with its exact child. */
    @Composable public fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, enabled: Boolean)
}

public interface AgentPageRenderer {
    public val status: AgentStatusRenderer
    public val separator: AgentHistorySeparatorRenderer
    /** Mounts the ordered content once; only unavailable geometry may omit a child slot. */
    @Composable public fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit)
}

/**
 * Borrows the exact Agent and stable children. Shared observations, pending-tool branches,
 * notification outlet and control wiring never resolve a replacement active tab.
 * Unmounting closes no child and does not cancel accepted owner work.
 */
@Composable
public expect fun AgentPageContent(
    viewModel: AgentViewModel,
    newLineKey: NewLineKey,
    renderer: AgentPageRenderer,
    onBrowseWorkingDirectory: () -> Unit,
    onBrowseSuggestedWorkingDirectory: (String) -> Unit,
    onOpenSettings: () -> Unit,
): Unit
