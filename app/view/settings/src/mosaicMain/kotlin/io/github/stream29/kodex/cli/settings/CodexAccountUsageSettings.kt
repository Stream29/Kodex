package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome

@Composable
internal fun CodexAccountUsageSettingsContent(
    state: SettingsAccountUsageState,
    onRefresh: () -> Unit,
    onUseReset: () -> Unit,
) {
    val snapshot = state.snapshotOrNull()
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text(
            "Codex usage",
            color = SettingsForeground,
            textStyle = TuiTheme.typography.title,
        )
        when {
            snapshot != null -> CodexAccountUsageSnapshotContent(snapshot)
            state is SettingsAccountUsageState.Unavailable ->
                Text("Sign in to view Codex usage.", color = SettingsSupportingForeground)

            state is SettingsAccountUsageState.Loading ->
                Text("Loading usage…", color = SettingsSupportingForeground, textStyle = TextStyle.Dim)

            state is SettingsAccountUsageState.Failed ->
                Text(state.message, color = SettingsErrorForeground, textStyle = TextStyle.Dim)
        }

        when (state) {
            is SettingsAccountUsageState.Loading -> if (snapshot != null) {
                Text("Refreshing usage…", color = SettingsSupportingForeground, textStyle = TextStyle.Dim)
            }

            is SettingsAccountUsageState.Failed -> if (snapshot != null) {
                Text(state.message, color = SettingsErrorForeground, textStyle = TextStyle.Dim)
            }

            is SettingsAccountUsageState.Redeeming ->
                Text("Using a reset…", color = SettingsSupportingForeground, textStyle = TextStyle.Dim)

            is SettingsAccountUsageState.Available,
            is SettingsAccountUsageState.Unavailable,
                -> Unit
        }

        if (state !is SettingsAccountUsageState.Unavailable) {
            val operationActive =
                state is SettingsAccountUsageState.Loading ||
                    state is SettingsAccountUsageState.Redeeming
            Row {
                TuiButton(
                    label = "Refresh",
                    modifier = Modifier.background(SettingsHomeBackground),
                    color = SettingsActionForeground,
                    enabled = !operationActive,
                    onClick = onRefresh,
                )
                Text(" ")
                TuiButton(
                    label = "Use reset",
                    modifier = Modifier.background(SettingsHomeBackground),
                    color = SettingsActionForeground,
                    enabled = state is SettingsAccountUsageState.Available &&
                        snapshot?.hasAvailableUsageReset() == true,
                    onClick = onUseReset,
                )
            }
        }
    }
}

private fun CodexAccountUsageSnapshot.hasAvailableUsageReset(): Boolean {
    val detailedCredits = resetCredits.credits.orEmpty()
    val availableCount = resetCredits.availableCount ?: detailedCredits.size.toLong()
    return availableCount > 0L || detailedCredits.isNotEmpty()
}

@Composable
private fun CodexAccountUsageSnapshotContent(snapshot: CodexAccountUsageSnapshot) {
    if (snapshot.rateLimits.isEmpty()) {
        Text("Rate limits unavailable", color = SettingsForeground)
    } else {
        snapshot.rateLimits.forEach { rateLimit ->
            Text(
                value = rateLimit.displayLine(),
                modifier = Modifier.fillMaxWidth(),
                color = SettingsForeground,
            )
        }
    }

    val lifetimeTokens = snapshot.tokenUsage?.lifetimeTokens
    Text(
        value = if (lifetimeTokens == null) {
            "Lifetime tokens: unavailable"
        } else {
            "Lifetime tokens: ${lifetimeTokens.groupedDecimal()}"
        },
        modifier = Modifier.fillMaxWidth(),
        color = SettingsForeground,
    )
    Text(
        value = snapshot.resetCredits.availableCount?.let { count ->
            "Usage limit resets: ${count.groupedDecimal()} available"
        } ?: "Usage limit resets: unavailable",
        modifier = Modifier.fillMaxWidth(),
        color = SettingsForeground,
    )

    if (
        snapshot.resetCredits.availableCount?.let { it > 0L } == true &&
        CodexAccountUsageSection.ResetCreditDetails in snapshot.unavailableSections
    ) {
        Text(
            "Reset details unavailable; the backend will choose a reset.",
            color = SettingsForeground,
            textStyle = TextStyle.Dim,
        )
    }
    if (CodexAccountUsageSection.TokenUsage in snapshot.unavailableSections) {
        Text(
            "Token activity details unavailable.",
            color = SettingsForeground,
            textStyle = TextStyle.Dim,
        )
    }
}

@Composable
internal fun BoxScope.UsageResetDialogHost(viewModel: GlobalSettingsViewModel) {
    val state by viewModel.usageReset.collectAsState()
    when (val current = state) {
        UsageResetState.Hidden -> Unit
        is UsageResetState.Choosing -> UsageResetPickerDialog(
            request = current.request,
            onSelect = viewModel::selectUsageReset,
            onDismiss = viewModel::dismissUsageReset,
        )

        is UsageResetState.Preparing -> UsageResetProgressDialog(
            title = "Usage limit resets",
            message = "Preparing ${current.option.title}…",
        )

        is UsageResetState.Confirming -> UsageResetConfirmationDialog(
            option = current.option,
            onConfirm = viewModel::confirmUsageReset,
            onBack = viewModel::returnToUsageResetChoices,
        )

        is UsageResetState.Consuming -> UsageResetProgressDialog(
            title = "Usage limit resets",
            message = "Resetting your usage…",
        )

        is UsageResetState.ConsumeFailed -> UsageResetFailureDialog(
            onRetry = viewModel::retryUsageReset,
            onDismiss = viewModel::dismissUsageReset,
        )

        UsageResetState.PreparationFailed -> UsageResetMessageDialog(
            title = "Usage limit resets",
            message = "Couldn't prepare a usage limit reset. Refresh usage and try again.",
            onDismiss = viewModel::dismissUsageReset,
            isError = true,
        )

        is UsageResetState.Completed -> UsageResetMessageDialog(
            title = "Usage limit resets",
            message = current.outcome.resultMessage(current.selectedCredit),
            onDismiss = viewModel::dismissUsageReset,
        )
    }
}

@Composable
private fun BoxScope.UsageResetPickerDialog(
    request: UsageResetRequest,
    onSelect: (UsageResetOption) -> Unit,
    onDismiss: () -> Unit,
) {
    UsageResetDialog(title = "Usage limit resets", onDismiss = onDismiss) {
        Text(
            value = "${request.availableCount} ${request.availableCount.resetLabel()} available.",
            modifier = Modifier.fillMaxWidth(),
            color = SettingsForeground,
        )
        request.options.forEachIndexed { index, option ->
            TuiButton(
                label = option.title,
                modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground),
                color = SettingsForeground,
                autoFocus = index == 0,
                onClick = { onSelect(option) },
            )
            option.expiresAt?.let { expiration ->
                Text(
                    value = "Expires $expiration",
                    modifier = Modifier.fillMaxWidth(),
                    color = SettingsForeground,
                    textStyle = TextStyle.Dim,
                )
            }
        }
        TuiDialogActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsActionBackground),
        ) {
            TuiButton(label = "Cancel", color = SettingsActionForeground, onClick = onDismiss)
        }
    }
}

@Composable
private fun BoxScope.UsageResetConfirmationDialog(
    option: UsageResetOption,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    UsageResetDialog(title = "Use this reset?", onDismiss = onBack) {
        Text(option.title, modifier = Modifier.fillMaxWidth(), color = SettingsForeground)
        option.expiresAt?.let { expiration ->
            Text(
                value = "Expires $expiration",
                modifier = Modifier.fillMaxWidth(),
                color = SettingsActionForeground,
            )
        }
        Text(
            value = option.description,
            modifier = Modifier.fillMaxWidth(),
            color = SettingsForeground,
        )
        TuiDialogActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsActionBackground),
        ) {
            TuiButton(
                label = "Go back",
                color = SettingsActionForeground,
                autoFocus = true,
                onClick = onBack,
            )
            TuiButton(label = "Use reset", color = SettingsActionForeground, onClick = onConfirm)
        }
    }
}

@Composable
private fun BoxScope.UsageResetProgressDialog(
    title: String,
    message: String,
) {
    UsageResetDialog(title = title, onDismiss = {}) {
        Text(message, modifier = Modifier.fillMaxWidth(), color = SettingsForeground)
    }
}

@Composable
private fun BoxScope.UsageResetFailureDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    UsageResetDialog(title = "Usage limit resets", onDismiss = onDismiss) {
        Text(
            value = "Couldn't reset usage. Please try again.",
            modifier = Modifier.fillMaxWidth(),
            color = SettingsErrorForeground,
        )
        TuiDialogActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsActionBackground),
        ) {
            TuiButton(
                label = "Close",
                color = SettingsActionForeground,
                autoFocus = true,
                onClick = onDismiss,
            )
            TuiButton(label = "Try again", color = SettingsActionForeground, onClick = onRetry)
        }
    }
}

@Composable
private fun BoxScope.UsageResetMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    isError: Boolean = false,
) {
    UsageResetDialog(title = title, onDismiss = onDismiss) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth(),
            color = if (isError) SettingsErrorForeground else SettingsForeground,
        )
        TuiDialogActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsActionBackground),
        ) {
            TuiButton(
                label = "Close",
                color = SettingsActionForeground,
                autoFocus = true,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun BoxScope.UsageResetDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, UsageResetMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = title,
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            content()
        }
    }
}

private fun CodexAccountRateLimit.displayLine(): String {
    val windows = listOfNotNull(primaryWindow, secondaryWindow)
        .joinToString(separator = " · ") { window -> window.displayLabel() }
        .ifEmpty { "unavailable" }
    val reached = if (limitReached || !allowed) " · limit reached" else ""
    return "$name: $windows$reached"
}

private fun CodexAccountRateLimitWindow.displayLabel(): String =
    "${durationSeconds.limitDurationLabel()} ${usedPercent}% used " +
        "(resets ${resetAfterSeconds.resetDelayLabel()})"

private fun Long.limitDurationLabel(): String {
    val seconds = coerceAtLeast(0L)
    return when {
        seconds > 0L && seconds % SecondsPerDay == 0L -> "${seconds / SecondsPerDay}d"
        seconds > 0L && seconds % SecondsPerHour == 0L -> "${seconds / SecondsPerHour}h"
        seconds > 0L && seconds % SecondsPerMinute == 0L -> "${seconds / SecondsPerMinute}m"
        else -> "${seconds}s"
    }
}

private fun Long.resetDelayLabel(): String {
    val seconds = coerceAtLeast(0L)
    return when {
        seconds == 0L -> "now"
        seconds >= SecondsPerDay -> "in ${seconds / SecondsPerDay}d"
        seconds >= SecondsPerHour -> "in ${seconds / SecondsPerHour}h"
        seconds >= SecondsPerMinute -> "in ${seconds / SecondsPerMinute}m"
        else -> "in ${seconds}s"
    }
}

private fun Long.groupedDecimal(): String {
    val raw = toString()
    val sign = raw.takeWhile { it == '-' }
    val digits = raw.removePrefix(sign)
    return sign + digits.reversed().chunked(3).joinToString(",").reversed()
}

private fun Long.resetLabel(): String =
    if (this == 1L) "usage limit reset" else "usage limit resets"

private fun CodexRateLimitResetOutcome.resultMessage(selectedCredit: Boolean): String =
    when (this) {
        CodexRateLimitResetOutcome.Reset -> "Usage reset."
        CodexRateLimitResetOutcome.AlreadyRedeemed ->
            "This reset was already used successfully."

        CodexRateLimitResetOutcome.NothingToReset ->
            "Your usage does not need a reset right now."

        CodexRateLimitResetOutcome.NoCredit -> if (selectedCredit) {
            "That reset is no longer available."
        } else {
            "No usage limit resets are available."
        }
    }

private const val SecondsPerMinute: Long = 60L
private const val SecondsPerHour: Long = 60L * SecondsPerMinute
private const val SecondsPerDay: Long = 24L * SecondsPerHour
private const val UsageResetMaximumWidth: Int = 72
