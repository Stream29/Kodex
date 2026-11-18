package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.cli.patch.PendingPatchToolEventView
import io.github.stream29.kodex.cli.patch.StablePatchToolEventView
import io.github.stream29.kodex.openai.StepStatus
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
    @Composable override fun Plan(value: HistoryPlanModel) {
        Column(Modifier.fillMaxWidth()) {
            Heading(
                if (value.pending) "• Updating Plan" else "• Update Plan",
                if (value.pending) HistoryTextStyle.Dim else HistoryTextStyle.Bold,
            )
            value.explanation?.let { Text("  └ $it", HistoryTextStyle.Dim) }
            if (value.steps.isEmpty()) {
                val indent = if (value.explanation == null) "  └ " else "    "
                value.emptyLabel?.let { Text("$indent$it", HistoryTextStyle.Dim) }
            } else value.steps.forEachIndexed { index, item ->
                val indent = if (value.explanation == null && index == 0) "  └ " else "    "
                val marker = when (item.status) {
                    StepStatus.Pending -> "[ ]"
                    StepStatus.InProgress -> "[>]"
                    StepStatus.Completed -> "[x]"
                }
                Text("$indent$marker ${item.step}", if (value.pending) HistoryTextStyle.Dim else HistoryTextStyle.Normal)
            }
        }
    }
    @Composable override fun Tool(
        summary: String, rawName: String?, status: String, expansionKey: Any,
        detailStyle: HistoryTextStyle, expandedSummary: String?,
        content: @Composable (HistoryToolDetails) -> Unit,
    ) {
        HistoryEventDisclosure(summary, status, expansionKey, expandedSummary) {
            val style = detailStyle.mosaicStyle()
            rawName?.takeIf(String::isNotBlank)?.let { WrappedHistoryText("Tool: $it", style) }
            content(MosaicHistoryToolDetails(ToolEventDetailsScope(style)))
        }
    }
    @Composable override fun TerminalExit(
        summary: String, status: String, expansionKey: Any, content: @Composable () -> Unit,
    ) {
        HistoryEventDisclosure(summary, status, expansionKey, showElapsed = false, content = content)
    }
    @Composable override fun StablePatch(event: StablePatch) {
        val expansion = LocalHistoryExpansion.current
        StablePatchToolEventView(
            event, expansion?.expanded?.invoke(), expansion?.toggle,
            LocalHistoryElapsed.current?.historyElapsedSuffix(),
        )
    }
    @Composable override fun PendingPatch(diff: Patch) { PendingPatchToolEventView(diff) }
}

@Composable
internal fun RequestUserInputHistoryRow(row: RequestUserInputHistoryRowModel) {
    val textStyle = when (row.role) {
        RequestUserInputHistoryRowRole.Header -> TextStyle.Bold
        RequestUserInputHistoryRowRole.Supporting -> TextStyle.Dim
        RequestUserInputHistoryRowRole.Body -> TextStyle.Unspecified
    }
    WrappedHistoryText(formatHistoryFormRowForTerminal(row), textStyle)
}

internal fun formatHistoryFormRowForTerminal(row: RequestUserInputHistoryRowModel): String = when (row.kind) {
    HistoryFormValueKind.Plain -> row.value
    HistoryFormValueKind.SelectedOption -> "[● ${row.value}]"
    HistoryFormValueKind.OptionDescription -> "  ${row.value}"
    HistoryFormValueKind.FreeText -> row.value.lines().mapIndexed { index, line ->
        "${if (index == 0) "  > " else "    "}$line"
    }.joinToString("\n")
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
