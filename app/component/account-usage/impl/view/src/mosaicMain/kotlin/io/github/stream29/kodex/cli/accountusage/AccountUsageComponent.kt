package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.accountusage.AccountUsageState
import io.github.stream29.kodex.app.accountusage.AccountUsageViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot

/**
 * Full usage summary/actions renderer. No reset dialogs, local reset state, constructor/disposal
 * refresh or child close on navigation. Set showOperationFailure false when Settings renders
 * the same application-owned flag. The host alone owns automatic page-entry refresh.
 */
@Composable
public fun AccountUsageComponent(viewModel: AccountUsageViewModel, showOperationFailure: Boolean = true) {
    val state by viewModel.state.collectAsState()
    AccountUsageContent(state, viewModel::refresh, viewModel::requestReset, viewModel::dismissFailure,
        showOperationFailure)
}

/** Every DTO branch is rendered without fabricating zeroes or crossing into reset ownership. */
@Composable
public fun AccountUsageContent(
    state: AccountUsageState,
    onRefresh: () -> Unit,
    onUseReset: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean = true,
) {
    if (state.closed) return
    val usage = state.usage
    val snapshot = usage.snapshotOrNull()
    Column(modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surface)) {
        Text("Codex usage", color = TuiTheme.colorScheme.onSurface, textStyle = TuiTheme.typography.title)
        when {
            snapshot != null -> AccountUsageSnapshotContent(snapshot)
            usage is SettingsAccountUsageState.Unavailable ->
                Text("Sign in to view Codex usage.", textStyle = TuiTheme.typography.supporting)
            usage is SettingsAccountUsageState.Loading ->
                Text("Loading usage…", textStyle = TextStyle.Dim)
            usage is SettingsAccountUsageState.Failed ->
                Text(usage.message, color = TuiTheme.colorScheme.error, textStyle = TextStyle.Dim)
        }
        when (usage) {
            is SettingsAccountUsageState.Loading -> if (snapshot != null) {
                Text("Refreshing usage…", textStyle = TextStyle.Dim)
            }
            is SettingsAccountUsageState.Failed -> if (snapshot != null) {
                Text(usage.message, color = TuiTheme.colorScheme.error, textStyle = TextStyle.Dim)
            }
            is SettingsAccountUsageState.Redeeming -> Text("Using a reset…", textStyle = TextStyle.Dim)
            is SettingsAccountUsageState.Available, SettingsAccountUsageState.Unavailable -> Unit
        }
        if (state.actionsVisible) {
            Row {
                TuiButton(label = "Refresh", enabled = state.refreshEnabled, autoFocus = true, onClick = onRefresh)
                Text(" ")
                TuiButton(label = "Use reset", enabled = state.resetEnabled, onClick = onUseReset)
            }
        }
        if (showOperationFailure && state.operationFailure) {
            Text("A settings operation failed.", color = TuiTheme.colorScheme.error)
            TuiButton(label = "Dismiss", onClick = onDismissFailure)
        }
    }
}

@Composable
private fun AccountUsageSnapshotContent(snapshot: CodexAccountUsageSnapshot) {
    if (snapshot.rateLimits.isEmpty()) {
        Text("Rate limits unavailable")
    } else {
        snapshot.rateLimits.forEach { Text(it.displayLine(), modifier = Modifier.fillMaxWidth()) }
    }
    val tokens = snapshot.tokenUsage?.lifetimeTokens
    Text(tokens?.let { "Lifetime tokens: ${it.groupedDecimal()}" } ?: "Lifetime tokens: unavailable")
    Text(snapshot.resetCredits.availableCount?.let { "Usage limit resets: ${it.groupedDecimal()} available" }
        ?: "Usage limit resets: unavailable")
    if (snapshot.resetCredits.availableCount?.let { it > 0L } == true &&
        (snapshot.resetCredits.credits == null ||
            CodexAccountUsageSection.ResetCreditDetails in snapshot.unavailableSections)) {
        Text("Reset details unavailable. Refresh usage to view available reset credits.", textStyle = TextStyle.Dim)
    }
    if (CodexAccountUsageSection.TokenUsage in snapshot.unavailableSections) {
        Text("Token activity details unavailable.", textStyle = TextStyle.Dim)
    }
}

private fun CodexAccountRateLimit.displayLine(): String {
    val windows = listOfNotNull(primaryWindow, secondaryWindow).joinToString(" ") { it.displayLabel() }
        .ifEmpty { "unavailable" }
    val reached = if (limitReached || !allowed) " limit reached" else ""
    return "$name: $windows$reached"
}

private fun CodexAccountRateLimitWindow.displayLabel(): String =
    "${durationSeconds.limitDurationLabel()} ${usedPercent}% used (resets ${resetAfterSeconds.resetDelayLabel()})"

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

private const val SecondsPerMinute: Long = 60L
private const val SecondsPerHour: Long = 60L * SecondsPerMinute
private const val SecondsPerDay: Long = 24L * SecondsPerHour
