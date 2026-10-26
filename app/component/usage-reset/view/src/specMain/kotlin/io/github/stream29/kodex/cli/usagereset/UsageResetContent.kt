package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel

/**
 * Shared workflow content borrowing the exact child. It neither refreshes nor closes it.
 * The host supplies modal geometry and routes dismiss/Escape only to the supplied callback.
 * Consuming supplies a no-op dismissal. Hidden mounts no frame.
 */
@Composable
public expect fun UsageResetContent(
    viewModel: UsageResetViewModel,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

/** Confirmation sends the captured object unchanged; retry only invokes the explicit retry command. */
@Composable
public expect fun UsageResetContent(
    state: UsageResetState,
    onSelect: (String) -> Unit,
    onConfirm: (UsageResetState.Confirming) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
internal expect fun UsageResetOptionButton(label: String, enabled: Boolean, onClick: () -> Unit)

@Composable
internal expect fun UsageResetAction(
    label: String, primary: Boolean = false, autoFocus: Boolean = false, onClick: () -> Unit,
)

@Composable
internal expect fun UsageResetActions(content: @Composable () -> Unit)
