package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Renders the Application-owned singleton [viewModel].
 *
 * [statusBarRows] and [statusBar] are visual layout inputs for the host's shared runtime controls.
 * [onSubmit] carries the input-confirmed revision to the Application owner. Unmounting this
 * screen does not close the Composer or cancel work already accepted by that owner.
 */
@Composable
public expect fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    statusBarRows: (Int) -> Int,
    onSubmit: (expectedRevision: Long) -> Unit,
    statusBar: @Composable (Int) -> Unit,
): Unit
