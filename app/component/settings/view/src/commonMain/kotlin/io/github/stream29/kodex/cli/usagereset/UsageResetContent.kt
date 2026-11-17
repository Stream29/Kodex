package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiActionRole
import io.github.stream29.kodex.app.ui.UiActions
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome

@Composable
public actual fun UsageResetContent(
    viewModel: UsageResetViewModel,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    UsageResetContent(
        state = state,
        onSelect = viewModel::select,
        onConfirm = viewModel::confirm,
        onBack = viewModel::back,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refreshChoices,
        onDismiss = viewModel::dismiss,
        frame = frame,
    )
}

@Composable
public actual fun UsageResetContent(
    state: UsageResetState,
    onSelect: (String) -> Unit,
    onConfirm: (UsageResetState.Confirming) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    // Recreate branch-local focus when the workflow moves between dialogs.
    key(state) {
        when (state) {
            UsageResetState.Hidden -> Unit
            is UsageResetState.Choosing -> frame("Usage limit resets", onDismiss) {
                UiColumn {
                    val count = state.request.availableCount
                    UiText(
                        "$count ${if (count == 1L) "usage limit reset" else "usage limit resets"} available.",
                    )
                    state.request.options.forEach { option ->
                        UiAction(
                            label = option.title,
                            onClick = { onSelect(option.creditId) },
                        )
                        OptionDetails(option)
                    }
                    UiActions {
                        UiAction("Cancel", onClick = onDismiss, autoFocus = true)
                    }
                }
            }

            is UsageResetState.Confirming -> frame("Use this reset?", onBack) {
                UiColumn {
                    UiText(state.option.title)
                    OptionDetails(state.option)
                    UiActions {
                        UiAction("Go back", onClick = onBack, autoFocus = true)
                        UiAction("Use reset", onClick = { onConfirm(state) }, role = UiActionRole.Primary)
                    }
                }
            }

            is UsageResetState.Consuming -> frame("Usage limit resets", {}) {
                UiColumn {
                    UiText("Resetting your usage…")
                }
            }

            is UsageResetState.ConsumeFailed -> frame("Usage limit resets", onDismiss) {
                UiColumn {
                    UiText(
                        "Couldn't confirm the reset result. The reset may have been used.",
                        role = UiTextRole.Error,
                    )
                    UiText(
                        "Usage refresh was requested; it may not be up to date. Select again to try again.",
                    )
                    UiActions {
                        UiAction("Close", onClick = onDismiss, autoFocus = true)
                        UiAction("Try again", onClick = onRetry, role = UiActionRole.Primary)
                    }
                }
            }

            UsageResetState.PreparationFailed -> frame("Usage limit resets", onDismiss) {
                UiColumn {
                    UiText(
                        "Reset details unavailable. Select a specific reset before using it.",
                        role = UiTextRole.Error,
                    )
                    UiText("Refresh usage and try again.")
                    UiActions {
                        UiAction("Close", onClick = onDismiss, autoFocus = true)
                        UiAction("Refresh", onClick = onRefresh, role = UiActionRole.Primary)
                    }
                }
            }

            is UsageResetState.Completed -> frame("Usage limit resets", onDismiss) {
                UiColumn {
                    UiText(state.outcome.resultMessage())
                    UiActions {
                        UiAction("Close", onClick = onDismiss, autoFocus = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionDetails(option: UsageResetOption) {
    UiText(
        option.expiresAt?.let { "Expires $it" } ?: "Expiry unknown",
        role = UiTextRole.Supporting,
    )
    UiText(option.description)
}

private fun CodexRateLimitResetOutcome.resultMessage(): String = when (this) {
    CodexRateLimitResetOutcome.Reset -> "Usage reset."
    CodexRateLimitResetOutcome.NothingToReset -> "Your usage does not need a reset right now."
    CodexRateLimitResetOutcome.AlreadyRedeemed -> "This reset was already used successfully."
    CodexRateLimitResetOutcome.NoCredit -> "That reset is no longer available."
}
