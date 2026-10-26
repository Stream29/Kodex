package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun AgentPageContent(
    viewModel: AgentViewModel,
    newLineKey: NewLineKey,
    renderer: AgentPageRenderer,
    onBrowseWorkingDirectory: () -> Unit,
    onBrowseSuggestedWorkingDirectory: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    key(AgentPageOwnerKey(viewModel)) {
        val state = viewModel.state.collectAsState().value
        val running = viewModel.running.collectAsState().value
        val pendingSteer = viewModel.pendingSteer.collectAsState().value
        val pendingRequest = viewModel.requestUserInput.state.collectAsState().value as? RequestUserInputState.Pending
        val pendingSuggestion = viewModel.suggestSubagentTask.state.collectAsState().value as? SuggestSubagentTaskState.Pending
        val settings = viewModel.settings.collectAsState().value
        val tokenCount = viewModel.tokenCount.collectAsState().value
        val composer = viewModel.composer.state.collectAsState().value
        val notification = viewModel.notification.collectAsState().value
        val duration = viewModel.history.activeTurnDuration.collectAsState().value
        val followsLatest = viewModel.history.followsLatest.collectAsState().value
        val hostInteractionPending = pendingRequest != null || pendingSuggestion != null
        val notice = notification?.takeIf { it.level == AgentNotificationLevel.Information }?.let {
            listOfNotNull(it.message, it.detail).joinToString(" ")
        }
        val inputs = AgentPageMeasureInputs(
            composer, pendingSteer, hostInteractionPending, notice, state, running, settings, tokenCount,
        )
        renderer.Frame(inputs) { slots ->
            slots.History(viewModel.history, viewModel.shellSessions, state.canEditHistory(running))
            pendingRequest?.let { slots.UserInput(viewModel.requestUserInput, it) }
            pendingSuggestion?.let {
                slots.Suggestion(viewModel.suggestSubagentTask, it, onBrowseSuggestedWorkingDirectory)
            }
            if (pendingSteer.isNotEmpty()) slots.PendingSteer()
            AgentHistorySeparatorContent(duration, !followsLatest, viewModel.history::requestScrollToLatest, renderer.separator)
            notice?.let { slots.Notice(it) }
            slots.Composer(viewModel.composer, newLineKey, enabled = !hostInteractionPending)
            AgentRuntimeStatusContent(
                viewModel, state, running, settings, tokenCount, renderer.status,
                onBrowseWorkingDirectory, onOpenSettings,
            )
        }
    }
}

private class AgentPageOwnerKey(private val owner: AgentViewModel) {
    override fun equals(other: Any?): Boolean = other is AgentPageOwnerKey && other.owner === owner
    override fun hashCode(): Int = 0
}
