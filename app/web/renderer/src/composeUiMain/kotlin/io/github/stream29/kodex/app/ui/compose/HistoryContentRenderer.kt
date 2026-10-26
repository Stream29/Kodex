package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.cli.patch.*
import io.github.stream29.kodex.utils.applypatch.Patch
import kotlin.time.Duration

@Composable
public actual fun rememberComposeHistoryContentRenderer(
    elapsed: Duration?, expanded: Boolean?, onToggleExpanded: (() -> Unit)?,
): HistoryContentRenderer {
    require((expanded == null) == (onToggleExpanded == null)) { "Expansion value and toggle must be supplied together" }
    val current = rememberUpdatedState(elapsed)
    val expansion = rememberUpdatedState(expanded)
    val toggle = rememberUpdatedState(onToggleExpanded)
    return remember { ComposeHistoryContentRenderer(current, expansion, toggle) }
}

private class ComposeHistoryContentRenderer(
    private val elapsed: State<Duration?>, private val expansion: State<Boolean?>,
    private val toggle: State<(() -> Unit)?>,
) : HistoryContentRenderer {
    @Composable override fun Frame(content: @Composable () -> Unit) { Column(Modifier.fillMaxWidth()) { content() } }
    @Composable override fun Row(row: RequestUserInputHistoryRowModel) { rememberComposeHistoryFormRenderer().Row(row) }
    @Composable override fun Heading(value: String, style: HistoryTextStyle) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialText(
                value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (style == HistoryTextStyle.Bold) FontWeight.Bold else FontWeight.Normal,
                color = if (style == HistoryTextStyle.Dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            elapsed.value?.let { MaterialText(it.historyElapsedSuffix(), style = MaterialTheme.typography.bodySmall) }
        }
    }
    @Composable override fun Text(value: String, style: HistoryTextStyle) { HistoryPlainText(value, style) }
    @Composable override fun Tool(
        summary: String, rawName: String?, status: String, expansionKey: Any,
        detailStyle: HistoryTextStyle, expandedSummary: String?,
        content: @Composable (HistoryToolDetails) -> Unit,
    ) {
        var localExpanded by remember(expansionKey) { mutableStateOf(false) }
        val expanded = expansion.value ?: localExpanded
        val details = remember(expansionKey, detailStyle) { ComposeHistoryToolDetails(detailStyle) }
        val color = when (status) {
            "failed" -> MaterialTheme.colorScheme.error
            "running", "streaming", "starting", "in_progress", "inprogress" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Column(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { if (expansion.value == null) localExpanded = !localExpanded else toggle.value?.invoke() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "History tool: $summary" },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaterialText(
                        "${if (expanded) "v" else ">"} ${if (expanded) expandedSummary ?: summary else summary}",
                        Modifier.weight(1f), color = color, maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    elapsed.value?.let { MaterialText(it.historyElapsedSuffix(), color = color, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (expanded) {
                rawName?.takeIf(String::isNotBlank)?.let { HistoryPlainText("Tool: $it", detailStyle) }
                content(details)
            }
        }
    }
    @Composable override fun Expandable(
        header: String, expansionKey: Any, style: HistoryTextStyle, content: @Composable () -> Unit,
    ) {
        var localExpanded by remember(expansionKey) { mutableStateOf(false) }
        val expanded = expansion.value ?: localExpanded
        Column(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { if (expansion.value == null) localExpanded = !localExpanded else toggle.value?.invoke() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "History disclosure: $header" },
            ) { Heading("${if (expanded) "v" else ">"} $header", style) }
            if (expanded) content()
        }
    }
    @Composable override fun StablePatch(event: StablePatchToolEvent) {
        val presentation = remember(event) { event.toStablePatchPresentation() }
        PatchContent(presentation, event)
    }
    @Composable override fun PendingPatch(diff: Patch) {
        val presentation = remember(diff) { diff.toPendingPatchPresentation() }
        PatchContent(presentation, diff)
    }
    @Composable private fun PatchContent(presentation: PatchPresentation, identity: Any) {
        var visible by remember(identity) { mutableIntStateOf(200) }
        Tool(
            presentation.header, null, when (presentation.status) {
                PatchPresentationStatus.Running -> "running"
                PatchPresentationStatus.Completed -> "succeeded"
                PatchPresentationStatus.Failed -> "failed"
            }, identity, HistoryTextStyle.Normal, null,
        ) {
            HistoryPlainText(presentation.rawToolName, HistoryTextStyle.Dim)
            presentation.lines.take(visible).forEach { line ->
                SelectionContainer {
                    MaterialText(
                        line.text, Modifier.fillMaxWidth(),
                        fontWeight = if (line.kind == PatchPresentationLineKind.File) FontWeight.Bold else FontWeight.Normal,
                        color = when (line.kind) {
                            PatchPresentationLineKind.Addition -> MaterialTheme.colorScheme.tertiary
                            PatchPresentationLineKind.Removal, PatchPresentationLineKind.Failure -> MaterialTheme.colorScheme.error
                            PatchPresentationLineKind.Metadata, PatchPresentationLineKind.Context -> MaterialTheme.colorScheme.onSurfaceVariant
                            PatchPresentationLineKind.File -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            val remaining = (presentation.lines.size - visible).coerceAtLeast(0)
            if (remaining > 0) TextButton(
                onClick = { visible = minOf(visible + 200, presentation.lines.size) },
                modifier = Modifier.semantics { contentDescription = "Show more patch lines" },
            ) { MaterialText("> Show next ${minOf(200, remaining)} lines $remaining remaining") }
        }
    }
}

private class ComposeHistoryToolDetails(private val style: HistoryTextStyle) : HistoryToolDetails {
    @Composable override fun Text(value: String, style: HistoryTextStyle) { HistoryPlainText(value, style) }
    @Composable override fun Section(label: String, content: @Composable () -> Unit) {
        var expanded by remember(label) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            TextButton(onClick = { expanded = !expanded }) { HistoryPlainText("${if (expanded) "v" else ">"} $label", style) }
            if (expanded) content()
        }
    }
}

@Composable
private fun HistoryPlainText(value: String, style: HistoryTextStyle) {
    SelectionContainer {
        MaterialText(
            value, Modifier.fillMaxWidth(),
            fontWeight = if (style == HistoryTextStyle.Bold) FontWeight.Bold else FontWeight.Normal,
            color = if (style == HistoryTextStyle.Dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
