package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.SettingsActionBackground
import io.github.stream29.kodex.cli.settings.SettingsActionButton
import io.github.stream29.kodex.cli.settings.SettingsContentButton
import io.github.stream29.kodex.cli.settings.SettingsDialogBackground
import io.github.stream29.kodex.cli.settings.SettingsErrorForeground
import io.github.stream29.kodex.cli.settings.SettingsForeground
import io.github.stream29.kodex.cli.settings.SettingsHeaderBackground
import io.github.stream29.kodex.cli.settings.SettingsPrimaryButton
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome

/**
 * Entire Mosaic dialog bound directly to its exact child. Composition removal
 * does not close this reusable child or cancel a submitted consume; Settings owns
 * page/source dismissal and final close. No Global Settings dependency or mirrored
 * dialog state exists here.
 */
@Composable
public fun BoxScope.UsageResetDialogHost(viewModel: UsageResetViewModel) {
    val state by viewModel.state.collectAsState()
    UsageResetDialog(
        state = state,
        onSelect = viewModel::select,
        onConfirm = viewModel::confirm,
        onBack = viewModel::back,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refreshChoices,
        onDismiss = viewModel::dismiss,
    )
}

/**
 * Renderer for every spec branch. Callbacks are commands only: confirming passes
 * the captured [state] object by identity, never rereads a parent or copies it.
 * Focus is Cancel/Go back/Close first. No renderer callback retries consumption.
 * Hidden emits nothing; Consuming has no actions and ignores Escape.
 */
@Composable
public fun BoxScope.UsageResetDialog(
    state: UsageResetState,
    onSelect: (String) -> Unit,
    onConfirm: (UsageResetState.Confirming) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Recreate branch-local focus when the workflow moves between dialogs.
    key(state) {
        when (state) {
            UsageResetState.Hidden -> Unit
            is UsageResetState.Choosing -> DialogFrame("Usage limit resets", onDismiss) {
                val count = state.request.availableCount
                Text(
                    "$count ${if (count == 1L) "usage limit reset" else "usage limit resets"} available.",
                    color = SettingsForeground,
                )
                state.request.options.forEach { option ->
                    SettingsContentButton(
                        label = option.title,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !option.creditId.isNullOrBlank(),
                        onClick = { option.creditId?.let(onSelect) },
                    )
                    OptionDetails(option)
                }
                Actions {
                    SettingsActionButton("Cancel", autoFocus = true, onClick = onDismiss)
                }
            }

            is UsageResetState.Confirming -> DialogFrame("Use this reset?", onBack) {
                Text(state.option.title, color = SettingsForeground)
                OptionDetails(state.option)
                Actions {
                    SettingsActionButton("Go back", autoFocus = true, onClick = onBack)
                    SettingsPrimaryButton("Use reset", onClick = { onConfirm(state) })
                }
            }

            is UsageResetState.Consuming -> DialogFrame("Usage limit resets", {}) {
                Text("Resetting your usage…", color = SettingsForeground)
            }

            is UsageResetState.ConsumeFailed -> DialogFrame("Usage limit resets", onDismiss) {
                Text(
                    "Couldn't confirm the reset result. The reset may have been used.",
                    color = SettingsErrorForeground,
                )
                Text(
                    "Usage refresh was requested; it may not be up to date. Select again to try again.",
                    color = SettingsForeground,
                )
                Actions {
                    SettingsActionButton("Close", autoFocus = true, onClick = onDismiss)
                    SettingsPrimaryButton("Try again", onClick = onRetry)
                }
            }

            UsageResetState.PreparationFailed -> DialogFrame("Usage limit resets", onDismiss) {
                Text(
                    "Reset details unavailable. Select a specific reset before using it.",
                    color = SettingsErrorForeground,
                )
                Text("Refresh usage and try again.", color = SettingsForeground)
                Actions {
                    SettingsActionButton("Close", autoFocus = true, onClick = onDismiss)
                    SettingsPrimaryButton("Refresh", onClick = onRefresh)
                }
            }

            is UsageResetState.Completed -> DialogFrame("Usage limit resets", onDismiss) {
                Text(state.outcome.resultMessage(state.selectedCredit), color = SettingsForeground)
                Actions {
                    SettingsActionButton("Close", autoFocus = true, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun OptionDetails(option: UsageResetOption) {
    Text(
        value = option.expiresAt?.let { "Expires $it" } ?: "Expiry unknown",
        modifier = Modifier.fillMaxWidth(),
        color = SettingsForeground,
        textStyle = TextStyle.Dim,
    )
    Text(option.description, modifier = Modifier.fillMaxWidth(), color = SettingsForeground)
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    TuiDialogActionRow(
        modifier = Modifier.fillMaxWidth().background(SettingsActionBackground),
        content = { content() },
    )
}

@Composable
private fun BoxScope.DialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                title,
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            content()
        }
    }
}

private fun CodexRateLimitResetOutcome.resultMessage(selectedCredit: Boolean): String = when (this) {
    CodexRateLimitResetOutcome.Reset -> "Usage reset."
    CodexRateLimitResetOutcome.NothingToReset -> "Your usage does not need a reset right now."
    CodexRateLimitResetOutcome.AlreadyRedeemed -> "This reset was already used successfully."
    CodexRateLimitResetOutcome.NoCredit -> if (selectedCredit) {
        "That reset is no longer available."
    } else {
        "No usage limit resets are available."
    }
}
