@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.history.HistoryListRenderer
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement

/** Retained widget position for one exact History owner, mounted by at most one surface at a time. */
public expect class ComposeHistoryViewState()

@Composable
public expect fun rememberComposeHistoryListRenderer(
    state: ComposeHistoryViewState,
    onOpenContextMenu: ((generation: Long, storageIndex: Int, item: HistoryItemViewModel, placement: ApplicationHistoryMenuPlacement) -> Unit)? = null,
): HistoryListRenderer
