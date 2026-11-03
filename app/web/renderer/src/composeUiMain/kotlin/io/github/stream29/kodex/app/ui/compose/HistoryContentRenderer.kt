package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.openai.StepStatus
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
                value, Modifier.weight(1f),
                fontWeight = if (style == HistoryTextStyle.Bold) FontWeight.Bold else FontWeight.Normal,
                color = if (style == HistoryTextStyle.Dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            elapsed.value?.let { MaterialText(it.historyElapsedSuffix(), style = MaterialTheme.typography.bodySmall) }
        }
    }
    @Composable override fun Text(value: String, style: HistoryTextStyle) { HistoryPlainText(value, style) }
    @Composable override fun AssistantText(value: String) { AssistantMarkdownText(value) }
    @Composable override fun AssistantStreamEligibility(isAssistant: Boolean?) {
        val report = LocalAssistantStreamEligibility.current
        SideEffect { report(isAssistant) }
    }
    @Composable override fun RecordedError(value: String, fallback: HistoryTextStyle) {
        HistoryPlainText(value, fallback, colorOverride = MaterialTheme.colorScheme.error)
    }
    @Composable override fun RecordedMetadata(value: String, fallback: HistoryTextStyle) {
        HistoryPlainText(value, HistoryTextStyle.Dim, textStyle = MaterialTheme.typography.bodySmall)
    }
    @Composable override fun RecordedJson(value: String, style: HistoryTextStyle) {
        HistoryPlainText(value, style, FontFamily.Monospace)
    }
    @Composable override fun RequestUserInput(event: StableRequestUserInputToolEvent, elapsed: Duration?) {
        HistoryRequestUserInputContent(event, elapsed, ComposeAnsweredQuestionHistoryFormRenderer)
    }
    @Composable override fun Plan(value: HistoryPlanModel) {
        Column(Modifier.fillMaxWidth()) {
            Heading(
                if (value.pending) "Updating Plan" else "Update Plan",
                if (value.pending) HistoryTextStyle.Dim else HistoryTextStyle.Bold,
            )
            ComposeHistoryPlanBody(value)
        }
    }
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
            HistoryDisclosureButton(
                onClick = { if (expansion.value == null) localExpanded = !localExpanded else toggle.value?.invoke() },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "History tool: $summary"
                    stateDescription = "$status; ${if (expanded) "Expanded" else "Collapsed"}"
                },
                stage = if (expansion.value != null) DisclosureFocusStage.Expanded else null,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryToolDisclosureMark(
                        if (expanded) HistoryDisclosureState.Expanded else HistoryDisclosureState.Collapsed, color,
                    )
                    MaterialText(
                        if (expanded) expandedSummary ?: summary else summary,
                        Modifier.weight(1f), color = color,
                        maxLines = if (!expanded && rawName == "exec_command") 1 else Int.MAX_VALUE,
                        overflow = if (!expanded && rawName == "exec_command") TextOverflow.Ellipsis else TextOverflow.Clip,
                        style = if (LocalHistoryPatchTitle.current) historyPatchTitleStyle() else historyToolTitleStyle(),
                    )
                    elapsed.value?.let { MaterialText(it.historyElapsedSuffix(), color = color, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (expanded) {
                rawName?.takeIf(String::isNotBlank)?.let { HistoryPlainText("Tool: $it", HistoryTextStyle.Dim) }
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
            CompactTextButton(
                onClick = { if (expansion.value == null) localExpanded = !localExpanded else toggle.value?.invoke() },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "History disclosure: $header"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            ) {
                HistoryDisclosureIndicator(if (expanded) HistoryDisclosureState.Expanded else HistoryDisclosureState.Collapsed)
                Box(Modifier.weight(1f)) { Heading(header, style) }
            }
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
        CompositionLocalProvider(LocalHistoryPatchTitle provides true) {
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
                            fontFamily = when (line.kind) {
                                PatchPresentationLineKind.Addition, PatchPresentationLineKind.Removal,
                                PatchPresentationLineKind.Context, PatchPresentationLineKind.File -> FontFamily.Monospace
                                PatchPresentationLineKind.Metadata, PatchPresentationLineKind.Failure -> null
                            },
                            fontWeight = if (line.kind == PatchPresentationLineKind.File) FontWeight.Bold else FontWeight.Normal,
                            softWrap = true,
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
                if (remaining > 0) CompactTextButton(
                    onClick = { visible = minOf(visible + 200, presentation.lines.size) },
                    modifier = Modifier.semantics {
                        contentDescription = "Show next ${minOf(200, remaining)} lines, $remaining remaining"
                    },
                ) { MaterialText("Show next ${minOf(200, remaining)} lines $remaining remaining") }
            }
        }
    }
}

private class ComposeHistoryToolDetails(private val style: HistoryTextStyle) : HistoryToolDetails {
    @Composable override fun Text(value: String, style: HistoryTextStyle) { HistoryPlainText(value, style) }
    @Composable override fun RecordedMetadata(value: String, fallback: HistoryTextStyle) {
        HistoryPlainText(value, HistoryTextStyle.Dim, textStyle = MaterialTheme.typography.bodySmall)
    }
    @Composable override fun RecordedOutput(value: String, style: HistoryTextStyle) {
        HistoryPlainText(value, style, FontFamily.Monospace)
    }
    @Composable override fun Section(label: String, content: @Composable () -> Unit) {
        var expanded by remember(label) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            CompactTextButton(onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            ) {
                HistoryToolDisclosureMark(if (expanded) HistoryDisclosureState.Expanded else HistoryDisclosureState.Collapsed)
                Box(Modifier.weight(1f)) {
                    MaterialText(
                        label, Modifier.fillMaxWidth(),
                        fontWeight = if (style == HistoryTextStyle.Bold) FontWeight.Bold else FontWeight.Normal,
                        color = if (style == HistoryTextStyle.Dim) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun HistoryPlainText(
    value: String, style: HistoryTextStyle, fontFamily: FontFamily? = null, colorOverride: Color? = null,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    SelectionContainer {
        MaterialText(
            value, Modifier.fillMaxWidth(),
            fontFamily = fontFamily,
            style = textStyle,
            fontWeight = if (style == HistoryTextStyle.Bold) FontWeight.Bold else FontWeight.Normal,
            color = colorOverride ?: if (style == HistoryTextStyle.Dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Shared by History and Index; status marks are visual, not editable controls. */
@Composable
internal fun ComposeHistoryPlanBody(value: HistoryPlanModel) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        value.explanation?.let { explanation ->
            SelectionContainer { MaterialText(explanation, color = color) }
        }
        if (value.steps.isEmpty()) {
            value.emptyLabel?.let { label -> SelectionContainer { MaterialText(label, color = color) } }
        } else {
            val markerWidth = 20.dp * LocalDensity.current.fontScale
            Column(Modifier.fillMaxWidth()) {
                value.steps.forEach { item ->
                    val status = when (item.status) {
                        StepStatus.Pending -> "Pending"
                        StepStatus.InProgress -> "In progress"
                        StepStatus.Completed -> "Completed"
                    }
                    val mark = when (item.status) {
                        StepStatus.Pending -> null
                        StepStatus.InProgress -> ">"
                        StepStatus.Completed -> "x"
                    }
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = status },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.width(markerWidth)) {
                            if (mark != null) MaterialText(mark, color = color)
                        }
                        SelectionContainer(Modifier.weight(1f)) {
                            MaterialText(
                                item.step,
                                color = if (value.pending) color else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
