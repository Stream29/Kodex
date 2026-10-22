package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel

/**
 * Entire Mosaic dialog bound directly to its exact child. Composition removal
 * does not close this reusable child or cancel a submitted consume; Settings owns
 * page/source dismissal and final close. No Global Settings dependency or mirrored
 * dialog state exists here.
 */
@Composable
public expect fun BoxScope.UsageResetDialogHost(viewModel: UsageResetViewModel): Unit

/**
 * Renderer for every spec branch. Callbacks are commands only: confirming passes
 * the captured [state] object by identity, never rereads a parent or copies it.
 * Focus is Cancel/Go back/Close first. No renderer callback retries consumption.
 * Hidden emits nothing; Consuming has no actions and ignores Escape.
 */
@Composable
public expect fun BoxScope.UsageResetDialog(
    state: UsageResetState,
    onSelect: (String) -> Unit,
    onConfirm: (UsageResetState.Confirming) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
): Unit
