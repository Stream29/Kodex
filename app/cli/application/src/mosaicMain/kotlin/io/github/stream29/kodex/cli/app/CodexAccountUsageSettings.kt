package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetAttempt
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.openai.accountusage.snapshotOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Instant

@Composable
internal fun CodexAccountUsageSettingsContent(
    state: CodexAccountUsageState,
    onRefresh: () -> Unit,
    onUseReset: () -> Unit,
) {
    val snapshot = state.snapshotOrNull()
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Codex usage", color = SettingsDialogForeground)
        when {
            snapshot != null -> CodexAccountUsageSnapshotContent(snapshot)
            state is CodexAccountUsageState.Unavailable ->
                Text("Sign in to view Codex usage.", color = SettingsDialogForeground)
            state is CodexAccountUsageState.Loading ->
                Text("Loading usage…", color = SettingsDialogForeground, textStyle = TextStyle.Dim)
            state is CodexAccountUsageState.Failed ->
                Text(state.message, color = SettingsDialogForeground, textStyle = TextStyle.Dim)
        }

        when (state) {
            is CodexAccountUsageState.Loading -> if (snapshot != null) {
                Text("Refreshing usage…", color = SettingsDialogForeground, textStyle = TextStyle.Dim)
            }

            is CodexAccountUsageState.Failed -> if (snapshot != null) {
                Text(state.message, color = SettingsDialogForeground, textStyle = TextStyle.Dim)
            }

            is CodexAccountUsageState.Redeeming ->
                Text("Using a reset…", color = SettingsDialogForeground, textStyle = TextStyle.Dim)

            is CodexAccountUsageState.Available,
            is CodexAccountUsageState.Unavailable,
                -> Unit
        }

        if (state !is CodexAccountUsageState.Unavailable) {
            val operationActive =
                state is CodexAccountUsageState.Loading || state is CodexAccountUsageState.Redeeming
            Row {
                TuiButton(
                    label = "Refresh",
                    modifier = Modifier.background(SettingsDialogHomeBackground),
                    color = SettingsDialogForeground,
                    enabled = !operationActive,
                    onClick = onRefresh,
                )
                Text(" ")
                TuiButton(
                    label = "Use reset",
                    modifier = Modifier.background(SettingsDialogHomeBackground),
                    color = SettingsDialogForeground,
                    enabled = state is CodexAccountUsageState.Available &&
                        snapshot?.usageResetRequestOrNull() != null,
                    onClick = onUseReset,
                )
            }
        }
    }
}

@Composable
private fun CodexAccountUsageSnapshotContent(snapshot: CodexAccountUsageSnapshot) {
    if (snapshot.rateLimits.isEmpty()) {
        Text("Rate limits unavailable", color = SettingsDialogForeground)
    } else {
        snapshot.rateLimits.forEach { rateLimit ->
            Text(
                value = rateLimit.displayLine(),
                modifier = Modifier.fillMaxWidth(),
                color = SettingsDialogForeground,
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
        color = SettingsDialogForeground,
    )
    Text(
        value = snapshot.resetCredits.availableCount?.let { count ->
            "Usage limit resets: ${count.groupedDecimal()} available"
        } ?: "Usage limit resets: unavailable",
        modifier = Modifier.fillMaxWidth(),
        color = SettingsDialogForeground,
    )

    if (
        snapshot.resetCredits.availableCount?.let { it > 0L } == true &&
        CodexAccountUsageSection.ResetCreditDetails in snapshot.unavailableSections
    ) {
        Text(
            "Reset details unavailable; the backend will choose a reset.",
            color = SettingsDialogForeground,
            textStyle = TextStyle.Dim,
        )
    }
    if (CodexAccountUsageSection.TokenUsage in snapshot.unavailableSections) {
        Text(
            "Token activity details unavailable.",
            color = SettingsDialogForeground,
            textStyle = TextStyle.Dim,
        )
    }
}

internal data class UsageResetRequest(
    val availableCount: Long,
    val options: List<UsageResetOption>,
)

internal data class UsageResetOption(
    val creditId: String?,
    val title: String,
    val description: String,
    val expiresAt: Instant?,
)

internal fun CodexAccountUsageSnapshot.usageResetRequestOrNull(): UsageResetRequest? {
    val detailedOptions = resetCredits.credits.orEmpty().map { credit ->
        UsageResetOption(
            creditId = credit.id,
            title = credit.title ?: "Full reset",
            description = credit.description ?: "Reset your current usage limits.",
            expiresAt = credit.expiresAt,
        )
    }
    val availableCount = resetCredits.availableCount
        ?: detailedOptions.size.toLong()
    if (availableCount <= 0L && detailedOptions.isEmpty()) return null
    return UsageResetRequest(
        availableCount = availableCount.coerceAtLeast(detailedOptions.size.toLong()),
        options = detailedOptions.ifEmpty {
            listOf(
                UsageResetOption(
                    creditId = null,
                    title = "Full reset",
                    description = "Reset your current usage limits.",
                    expiresAt = null,
                ),
            )
        },
    )
}

@Composable
internal fun BoxScope.UsageResetDialogHost(
    request: UsageResetRequest,
    store: CodexAccountUsageStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dialogState by remember(request) {
        mutableStateOf<UsageResetDialogState>(UsageResetDialogState.Picker(request))
    }

    fun select(option: UsageResetOption) {
        dialogState = UsageResetDialogState.Preparing(option)
        scope.launch {
            try {
                val attempt = store.createResetAttempt(option.creditId)
                dialogState = UsageResetDialogState.Confirmation(
                    request = request,
                    option = option,
                    attempt = attempt,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                dialogState = UsageResetDialogState.PreparationFailed
            }
        }
    }

    fun consume(confirmation: UsageResetDialogState.Confirmation) {
        dialogState = UsageResetDialogState.Consuming(confirmation)
        scope.launch {
            try {
                val outcome = store.consumeResetAttempt(confirmation.attempt)
                dialogState = UsageResetDialogState.Completed(
                    message = outcome.resultMessage(confirmation.option.creditId != null),
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                dialogState = UsageResetDialogState.ConsumeFailed(confirmation)
            }
        }
    }

    when (val current = dialogState) {
        is UsageResetDialogState.Picker -> UsageResetPickerDialog(
            request = current.request,
            onSelect = ::select,
            onDismiss = onDismiss,
        )

        is UsageResetDialogState.Preparing -> UsageResetProgressDialog(
            title = "Usage limit resets",
            message = "Preparing ${current.option.title}…",
        )

        is UsageResetDialogState.Confirmation -> UsageResetConfirmationDialog(
            option = current.option,
            onConfirm = { consume(current) },
            onBack = { dialogState = UsageResetDialogState.Picker(current.request) },
        )

        is UsageResetDialogState.Consuming -> UsageResetProgressDialog(
            title = "Usage limit resets",
            message = "Resetting your usage…",
        )

        is UsageResetDialogState.ConsumeFailed -> UsageResetFailureDialog(
            onRetry = { consume(current.confirmation) },
            onDismiss = onDismiss,
        )

        UsageResetDialogState.PreparationFailed -> UsageResetMessageDialog(
            title = "Usage limit resets",
            message = "Couldn't prepare a usage limit reset. Refresh usage and try again.",
            onDismiss = onDismiss,
        )

        is UsageResetDialogState.Completed -> UsageResetMessageDialog(
            title = "Usage limit resets",
            message = current.message,
            onDismiss = onDismiss,
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
            color = SettingsDialogForeground,
        )
        request.options.forEachIndexed { index, option ->
            TuiButton(
                label = option.title,
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground),
                color = SettingsDialogForeground,
                autoFocus = index == 0,
                onClick = { onSelect(option) },
            )
            option.expiresAt?.let { expiration ->
                Text(
                    value = "Expires $expiration",
                    modifier = Modifier.fillMaxWidth(),
                    color = SettingsDialogForeground,
                    textStyle = TextStyle.Dim,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
            TuiButton(label = "Cancel", color = SettingsDialogForeground, onClick = onDismiss)
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
        Text(option.title, modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
        option.expiresAt?.let { expiration ->
            Text(
                value = "Expires $expiration",
                modifier = Modifier.fillMaxWidth(),
                color = SettingsDialogForeground,
            )
        }
        Text(
            value = option.description,
            modifier = Modifier.fillMaxWidth(),
            color = SettingsDialogForeground,
        )
        Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
            TuiButton(label = "Use reset", color = SettingsDialogForeground, onClick = onConfirm)
            Text(" ")
            TuiButton(
                label = "Go back",
                color = SettingsDialogForeground,
                autoFocus = true,
                onClick = onBack,
            )
        }
    }
}

@Composable
private fun BoxScope.UsageResetProgressDialog(
    title: String,
    message: String,
) {
    UsageResetDialog(title = title, onDismiss = {}) {
        Text(message, modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
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
            color = SettingsDialogForeground,
        )
        Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
            TuiButton(label = "Try again", color = SettingsDialogForeground, onClick = onRetry)
            Text(" ")
            TuiButton(
                label = "Close",
                color = SettingsDialogForeground,
                autoFocus = true,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun BoxScope.UsageResetMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    UsageResetDialog(title = title, onDismiss = onDismiss) {
        Text(message, modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
        Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
            TuiButton(
                label = "Close",
                color = SettingsDialogForeground,
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
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
            Text(
                value = title,
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TextStyle.Bold,
            )
            content()
        }
    }
}

private sealed interface UsageResetDialogState {
    data class Picker(val request: UsageResetRequest) : UsageResetDialogState

    data class Preparing(
        val option: UsageResetOption,
    ) : UsageResetDialogState

    data class Confirmation(
        val request: UsageResetRequest,
        val option: UsageResetOption,
        val attempt: CodexRateLimitResetAttempt,
    ) : UsageResetDialogState

    data class Consuming(
        val confirmation: Confirmation,
    ) : UsageResetDialogState

    data class ConsumeFailed(
        val confirmation: Confirmation,
    ) : UsageResetDialogState

    data object PreparationFailed : UsageResetDialogState

    data class Completed(val message: String) : UsageResetDialogState
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
