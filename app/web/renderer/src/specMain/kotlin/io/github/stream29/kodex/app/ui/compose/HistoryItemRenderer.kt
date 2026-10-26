package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.history.HistoryItemRenderer
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement

@Composable
public expect fun rememberComposeHistoryItemRenderer(
    onOpenContextMenu: ((HistoryItemViewModel, storageIndex: Int, placement: ApplicationHistoryMenuPlacement) -> Unit)? = null,
): HistoryItemRenderer
