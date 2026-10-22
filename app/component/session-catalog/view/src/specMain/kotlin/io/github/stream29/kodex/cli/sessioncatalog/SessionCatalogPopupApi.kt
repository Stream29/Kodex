package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel

/**
 * Complete Select Session renderer for one host-owned catalog child.
 *
 * Refreshes once on mounting [viewModel]; Unloaded/Loading show animated progress,
 * Loaded empty shows the empty message, and completed rows preserve backend order.
 * Menu dates/labels/running flags use that captured snapshot without extra I/O.
 * Geometry, focus, scrolling and animation are renderer-only; business deletion
 * renders the VM's exact existing Session Delete child. Unmount cancels renderer
 * callers and dismisses its captured confirmation, not the catalog/backend.
 *
 * Open and fork failures reach [onFailure] without dismissing the popup.
 * Its throwing default reaches the host coroutine exception boundary, like other
 * throwing commands. Cancellation is always rethrown, never reported as failure.
 * No failure is rendered as a successfully loaded empty list.
 */
@Composable
public expect fun BoxScope.SessionCatalogPopup(
    viewModel: SessionCatalogViewModel,
    onFailure: (Throwable) -> Unit = { throw it },
): Unit
