package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.cli.components.TuiTheme
import kotlin.time.Duration

@Composable
public actual fun StableRequestUserInputToolEvent.renderRequestUserInput(elapsed: Duration?) {
    HistoryRequestUserInputContent(this, elapsed, MosaicHistoryFormRenderer)
}

@Composable
public actual fun StableSuggestSubagentTaskToolEvent.renderSuggestion(elapsed: Duration?) {
    HistorySuggestionContent(this, elapsed, MosaicHistoryFormRenderer)
}

private object MosaicHistoryFormRenderer : HistoryFormRenderer {
    @Composable override fun Frame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
    @Composable override fun Row(row: RequestUserInputHistoryRowModel) { RequestUserInputHistoryRow(row) }
}

@Composable
public actual fun RequestUserInputHistoryRow(row: RequestUserInputHistoryRowModel) {
    val textStyle = when (row.role) {
        RequestUserInputHistoryRowRole.Header -> TextStyle.Bold
        RequestUserInputHistoryRowRole.Supporting, RequestUserInputHistoryRowRole.Error -> TextStyle.Dim
        RequestUserInputHistoryRowRole.Body -> TextStyle.Unspecified
    }
    WrappedHistoryText(
        row.value, textStyle,
        if (row.role == RequestUserInputHistoryRowRole.Error) TuiTheme.colorScheme.error else Color.Unspecified,
    )
}
