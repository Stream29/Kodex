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
    onOpenSettings: () -> Unit,
) {
    key(AgentPageOwnerKey(viewModel)) {
        val state = viewModel.state.collectAsState().value
        val running = viewModel.running.collectAsState().value
        val pendingSteer = viewModel.pendingSteer.collectAsState().value
        val pendingRequest = viewModel.requestUserInput.state.collectAsState().value as? RequestUserInputState.Pending
        val settings = viewModel.settings.collectAsState().value
        val tokenCount = viewModel.tokenCount.collectAsState().value
        val composer = viewModel.composer.state.collectAsState().value
        val duration = viewModel.history.activeTurnDuration.collectAsState().value
        val followsLatest = viewModel.history.followsLatest.collectAsState().value
        val inputs = AgentPageMeasureInputs(
            composer = composer,
            pendingSteer = pendingSteer,
            state = state,
            running = running,
            settings = settings,
            tokenCount = tokenCount,
            pendingToolPanelCount = if (pendingRequest != null) 1 else 0,
        )
        renderer.Frame(inputs) { slots ->
            slots.History(viewModel.history, viewModel.shellSessions, state.canEditHistory(running))
            pendingRequest?.let { slots.UserInput(viewModel.requestUserInput, it) }
            if (pendingSteer.isNotEmpty()) slots.PendingSteer()
            AgentHistorySeparatorContent(duration, !followsLatest, viewModel.history::requestScrollToLatest, renderer.separator)
            slots.Composer(viewModel.composer, newLineKey, autoFocus = pendingRequest == null)
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
