package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
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
    statusBarRows: Int,
    onSubmit: (expectedRevision: Long) -> Unit,
    statusBar: @Composable () -> Unit,
): Unit

/**
 * Draft input with the original blank-history space, separator, bounded wrapping and cursor.
 *
 * The widget buffer belongs to the renderer; accepted edits are sent to the one Composer child.
 * Submit invokes the captured owner's [onSubmit], not Composer's persisted-Agent submission port.
 * A tab switch disposes only widget state, never the borrowed Composer or its owner.
 */
@Composable
public expect fun NewSessionContent(
    composerViewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onSubmit: (expectedRevision: Long) -> Unit,
): Unit
