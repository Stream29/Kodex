package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import kotlin.time.Duration

/** Read-only presentation shared by History, Index and Sidebar; never an editable draft. */
@Immutable
public data class RequestUserInputHistoryRowModel(
    public val value: String,
    public val role: RequestUserInputHistoryRowRole,
)

public enum class RequestUserInputHistoryRowRole { Header, Body, Supporting, Error }

/** Shared History/Index elapsed display, rounding nearest-millisecond ties away from zero. */
public expect fun Duration.roundToMilliseconds(): Duration

/** Only layout and typography. Row order, redaction and result branches belong to shared content. */
public interface HistoryFormRenderer {
    @Composable public fun Frame(content: @Composable () -> Unit)
    @Composable public fun Row(row: RequestUserInputHistoryRowModel)
}

/** Secret answers are redacted before any renderer sees them. */
public expect fun StableRequestUserInputToolEvent.requestUserInputHistoryRows(
    elapsed: Duration? = null,
): List<RequestUserInputHistoryRowModel>

/** Preserve task order and pair each accepted Session URI with its original task index. */
public expect fun StableSuggestSubagentTaskToolEvent.suggestSubagentTaskHistoryRows(
    elapsed: Duration? = null,
): List<RequestUserInputHistoryRowModel>

@Composable
public expect fun HistoryRequestUserInputContent(
    event: StableRequestUserInputToolEvent, elapsed: Duration?, renderer: HistoryFormRenderer,
)

@Composable
public expect fun HistorySuggestionContent(
    event: StableSuggestSubagentTaskToolEvent, elapsed: Duration?, renderer: HistoryFormRenderer,
)
