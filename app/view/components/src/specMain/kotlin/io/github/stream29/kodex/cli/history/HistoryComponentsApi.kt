package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import kotlin.time.Duration

/**
 * Shared read-only History/Index typography. Mosaic Text clips instead of wrapping;
 * subcomposition from the finite incoming width keeps lazy rows independently measurable.
 */
@Composable
public expect fun WrappedHistoryText(
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
    color: Color = Color.Unspecified,
): Unit

/** Preserves the shared elapsed-duration display's nearest-millisecond rounding. */
public expect fun Duration.roundToMilliseconds(): Duration

/** Renders a completed request-user-input exchange as the read-only form the user answered. */
@Composable
public expect fun StableRequestUserInputToolEvent.renderRequestUserInput(
    elapsed: Duration?,
): Unit

/** One renderer-neutral row in the completed read-only request-user-input form. */
@Immutable
public data class RequestUserInputHistoryRowModel(
    public val value: String,
    public val role: RequestUserInputHistoryRowRole,
)

/** Visual role shared by the main History and History Index hover renderers. */
public enum class RequestUserInputHistoryRowRole {
    Header,
    Body,
    Supporting,
    Error,
}

/** Projects the completed exchange into the exact rows used by the read-only History form. */
public expect fun StableRequestUserInputToolEvent.requestUserInputHistoryRows(
    elapsed: Duration? = null,
): List<RequestUserInputHistoryRowModel>

/** Renders one shared read-only request-user-input row. */
@Composable
public expect fun RequestUserInputHistoryRow(row: RequestUserInputHistoryRowModel): Unit

@Composable
public expect fun StableSuggestSubagentTaskToolEvent.renderSuggestion(elapsed: Duration?): Unit

/** Uses the same read-only typography and option rows as completed user input. */
public expect fun StableSuggestSubagentTaskToolEvent.suggestSubagentTaskHistoryRows(
    elapsed: Duration? = null,
): List<RequestUserInputHistoryRowModel>
