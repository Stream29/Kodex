package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

@Composable
internal fun AgentHistoryView(
    model: AgentHistoryViewModel,
    shellSessions: AgentShellSessionRegistry,
    viewState: AgentHistoryViewState = remember(model) { AgentHistoryViewState() },
    onOpenEntryContextMenu: ((
        generation: Long, storageIndex: Int, item: HistoryItemViewModel,
        anchor: TuiPopupAnchor, clickPosition: IntOffset?,
    ) -> Unit)? = null,
) {
    AgentHistoryContent(model, MosaicHistoryListRenderer(viewState, onOpenEntryContextMenu), shellSessions)
}
