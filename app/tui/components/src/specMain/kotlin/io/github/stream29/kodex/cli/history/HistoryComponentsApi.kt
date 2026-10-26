package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
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

/** Renders a completed request-user-input exchange as the read-only form the user answered. */
@Composable
public expect fun StableRequestUserInputToolEvent.renderRequestUserInput(
    elapsed: Duration?,
): Unit

/** Renders one shared read-only request-user-input row. */
@Composable
public expect fun RequestUserInputHistoryRow(row: RequestUserInputHistoryRowModel): Unit

@Composable
public expect fun StableSuggestSubagentTaskToolEvent.renderSuggestion(elapsed: Duration?): Unit
