package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.stream29.kodex.app.accountusage.AccountUsageState
import io.github.stream29.kodex.app.accountusage.AccountUsageViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot

@Composable
public actual fun AccountUsageComponent(viewModel: AccountUsageViewModel, showOperationFailure: Boolean) {
    val state by viewModel.state.collectAsState()
    AccountUsageContent(state, viewModel::refresh, viewModel::requestReset, viewModel::dismissFailure,
        showOperationFailure)
}

@Composable
public actual fun AccountUsageContent(
    state: AccountUsageState,
    onRefresh: () -> Unit,
    onUseReset: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean,
) {
    if (state.closed) return
    val usage = state.usage
    val snapshot = usage.snapshotOrNull()
    AccountUsageFrame {
        UiText("Codex usage", UiTextRole.Title)
        when {
            snapshot != null -> AccountUsageSnapshotContent(snapshot)
            usage is SettingsAccountUsageState.Unavailable ->
                UiText("Sign in to view Codex usage.", UiTextRole.Supporting)
            usage is SettingsAccountUsageState.Loading ->
                UiText("Loading usage…", UiTextRole.Supporting)
            usage is SettingsAccountUsageState.Failed ->
                UiText(usage.message, UiTextRole.Error)
        }
        when (usage) {
            is SettingsAccountUsageState.Loading -> if (snapshot != null) {
                UiText("Refreshing usage…", UiTextRole.Supporting)
            }
            is SettingsAccountUsageState.Failed -> if (snapshot != null) {
                UiText(usage.message, UiTextRole.Error)
            }
            is SettingsAccountUsageState.Redeeming -> UiText("Using a reset…", UiTextRole.Supporting)
            is SettingsAccountUsageState.Available, SettingsAccountUsageState.Unavailable -> Unit
        }
        if (state.actionsVisible) {
            AccountUsageActions(
                refresh = { UiAction("Refresh", enabled = state.refreshEnabled, autoFocus = true, onClick = onRefresh) },
                reset = { UiAction("Use reset", enabled = state.resetEnabled, onClick = onUseReset) },
            )
        }
        if (showOperationFailure && state.operationFailure) {
            UiText("A settings operation failed.", UiTextRole.Error)
            UiAction("Dismiss", onClick = onDismissFailure)
        }
    }
}

@Composable
private fun AccountUsageSnapshotContent(snapshot: CodexAccountUsageSnapshot) {
    if (snapshot.rateLimits.isEmpty()) {
        UiText("Rate limits unavailable")
    } else {
        snapshot.rateLimits.forEach { UiText(it.displayLine()) }
    }
    val tokens = snapshot.tokenUsage?.lifetimeTokens
    UiText(tokens?.let { "Lifetime tokens: ${it.groupedDecimal()}" } ?: "Lifetime tokens: unavailable")
    UiText(snapshot.resetCredits.availableCount?.let { "Usage limit resets: ${it.groupedDecimal()} available" }
        ?: "Usage limit resets: unavailable")
    if (snapshot.resetCredits.availableCount?.let { it > 0L } == true &&
        (snapshot.resetCredits.credits == null ||
            CodexAccountUsageSection.ResetCreditDetails in snapshot.unavailableSections)) {
        UiText("Reset details unavailable. Refresh usage to view available reset credits.", UiTextRole.Supporting)
    }
    if (CodexAccountUsageSection.TokenUsage in snapshot.unavailableSections) {
        UiText("Token activity details unavailable.", UiTextRole.Supporting)
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
