package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

@Composable
public actual fun AgentHistoryView(
    model: AgentHistoryViewModel, shellSessions: AgentShellSessionRegistry, viewState: AgentHistoryViewState,
    onOpenEntryContextMenu: ((
        generation: Long, storageIndex: Int, item: HistoryItemViewModel,
        anchor: TuiPopupAnchor, clickPosition: IntOffset?,
    ) -> Unit)?,
) {
    AgentHistoryContent(model, MosaicHistoryListRenderer(viewState, onOpenEntryContextMenu), shellSessions)
}
