package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState

/**
 * Root menu command wiring over captured owners and boundaries, without retries.
 * Each admission callback must synchronously compare and clear its exact current opening,
 * returning false for stale callbacks. It is also used to dismiss invalidated openings.
 * Ordinary failures use the existing application outlet; cancellation only ends the caller's wait.
 * After a successful edit, focus is requested after a frame only if that Agent is still selected
 * and no Application popup is open. The host must focus only its matching mounted editor.
 */
@Composable
public expect fun ApplicationMenusContent(
    viewModel: ApplicationViewModel,
    navigation: ApplicationNavigationState,
    selectedAgent: AgentViewModel?,
    tabOpening: ApplicationTabMenuTarget?,
    historyOpening: ApplicationHistoryMenuOpening?,
    renderer: ApplicationMenuRenderer,
    admitTab: (ApplicationTabMenuTarget) -> Boolean,
    admitHistory: (ApplicationHistoryMenuOpening) -> Boolean,
    focusEditedComposer: (AgentViewModel) -> Unit,
    onOperationFailure: (Throwable) -> Unit,
): Unit
