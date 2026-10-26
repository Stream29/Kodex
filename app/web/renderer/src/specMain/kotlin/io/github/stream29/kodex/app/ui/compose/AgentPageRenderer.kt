package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.UiEditorFocusHandle
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.app.AgentPageRenderer
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement

/** The root retains [historyState] by exact History owner; this factory does not own that child. */
@Composable
public expect fun rememberComposeAgentPageRenderer(
    historyState: ComposeHistoryViewState,
    onOpenHistoryContextMenu: ((generation: Long, storageIndex: Int, item: HistoryItemViewModel, placement: ApplicationHistoryMenuPlacement) -> Unit)? = null,
    composerFocus: UiEditorFocusHandle? = null,
): AgentPageRenderer
