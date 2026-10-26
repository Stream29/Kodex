package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.cli.patch.StablePatchToolEventView
import io.github.stream29.kodex.cli.patch.PendingPatchToolEventView
import io.github.stream29.kodex.utils.applypatch.Patch

internal class MosaicHistoryContentRenderer : HistoryContentRenderer {
    @Composable override fun Frame(content: @Composable () -> Unit) { Column(Modifier.fillMaxWidth()) { content() } }
    @Composable override fun Row(row: RequestUserInputHistoryRowModel) { RequestUserInputHistoryRow(row) }
    @Composable override fun Heading(value: String, style: HistoryTextStyle) {
        HistoryItemHeader(value, textStyle = style.mosaicStyle())
    }
    @Composable override fun Text(value: String, style: HistoryTextStyle) {
        WrappedHistoryText(value, style.mosaicStyle())
    }
    @Composable override fun Tool(
        summary: String, rawName: String?, status: String, expansionKey: Any,
        detailStyle: HistoryTextStyle, expandedSummary: String?,
        content: @Composable (HistoryToolDetails) -> Unit,
    ) {
        ToolEvent(summary, rawName, status, expansionKey, detailStyle.mosaicStyle(), expandedSummary) {
            content(MosaicHistoryToolDetails(this))
        }
    }
    @Composable override fun Expandable(
        header: String, expansionKey: Any, style: HistoryTextStyle, content: @Composable () -> Unit,
    ) { ExpandableHistoryEvent(header, expansionKey, style.mosaicStyle(), content) }
    @Composable override fun StablePatch(event: StablePatchToolEvent) {
        val expansion = LocalHistoryExpansion.current
        StablePatchToolEventView(
            event, expansion?.expanded?.invoke(), expansion?.toggle,
            LocalHistoryElapsed.current?.historyElapsedSuffix(),
        )
    }
    @Composable override fun PendingPatch(diff: Patch) { PendingPatchToolEventView(diff) }
}

private class MosaicHistoryToolDetails(private val original: ToolEventDetailsScope) : HistoryToolDetails {
    @Composable override fun Text(value: String, style: HistoryTextStyle) { WrappedHistoryText(value, style.mosaicStyle()) }
    @Composable override fun Section(label: String, content: @Composable () -> Unit) { original.section(label, content) }
}

internal fun HistoryTextStyle.mosaicStyle(): TextStyle = when (this) {
    HistoryTextStyle.Normal -> TextStyle.Unspecified
    HistoryTextStyle.Dim -> TextStyle.Dim
    HistoryTextStyle.Bold -> TextStyle.Bold
}
