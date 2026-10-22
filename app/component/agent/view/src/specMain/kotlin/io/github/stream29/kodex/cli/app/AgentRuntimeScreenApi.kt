package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.history.AgentHistoryViewState
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Borrows the exact Agent and its existing children. Unmounting does not close them.
 * Menu callbacks retain the original generation, storage index and actual item.
 * [historyViewState] owns only borrowed renderer position/input; the root retains it across
 * tab unmounts by exact History identity and discards it on binding replacement or close.
 */
@Composable
public expect fun AgentRuntimeScreen(
    viewModel: AgentViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    dropdowns: RuntimeConfigurationDropdowns,
    suggestionDropdowns: SuggestSubagentTaskDropdowns,
    onOpenHistoryEntryContextMenu: (
        generation: Long,
        storageIndex: Int,
        item: io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit,
    onBrowseWorkingDirectory: () -> Unit,
    onBrowseSuggestedWorkingDirectory: (String) -> Unit,
    onOpenSettings: () -> Unit,
    composerFocusRequester: FocusRequester? = null,
    historyViewState: AgentHistoryViewState = remember(viewModel.history) { AgentHistoryViewState() },
): Unit
