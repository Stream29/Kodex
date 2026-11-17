package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.openai.PlanItemArg
import kotlin.time.Duration

/** Read-only presentation shared by History, Index and Sidebar; never an editable draft. */
@Immutable
public data class RequestUserInputHistoryRowModel(
    public val value: String,
    public val role: RequestUserInputHistoryRowRole,
    public val kind: HistoryFormValueKind = HistoryFormValueKind.Plain,
)

public enum class RequestUserInputHistoryRowRole { Header, Body, Supporting }

/** Presentation meaning; the value itself contains no renderer-specific decoration. */
public enum class HistoryFormValueKind { Plain, SelectedOption, FreeText, OptionDescription }

/** Read-only plan content; renderers own status decoration, never plan state. */
public data class HistoryPlanModel(
    public val explanation: String?,
    public val steps: List<PlanItemArg>,
    public val pending: Boolean,
    public val emptyLabel: String?,
)

/** Shared History/Index elapsed display, rounding nearest-millisecond ties away from zero. */
public expect fun Duration.roundToMilliseconds(): Duration

/** Only layout and typography. Row order, redaction and result branches belong to shared content. */
public interface HistoryFormRenderer {
    @Composable public fun Frame(content: @Composable () -> Unit)
    @Composable public fun Row(row: RequestUserInputHistoryRowModel)
}

/** Secret answers are redacted before any renderer sees them. */
public expect fun StableAskUserEvent.requestUserInputHistoryRows(
    elapsed: Duration? = null,
): List<RequestUserInputHistoryRowModel>

@Composable
public expect fun HistoryRequestUserInputContent(
    event: StableAskUserEvent, elapsed: Duration?, renderer: HistoryFormRenderer,
)
