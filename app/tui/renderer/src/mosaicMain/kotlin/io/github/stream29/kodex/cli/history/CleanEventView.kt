package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.*
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.cli.components.*
import kotlin.time.Duration

@Composable
public actual fun StableCleanEvent.render(shellSessions: AgentShellSessionRegistry?) {
    render(shellSessions, expansion = null, elapsed = null)
}

@Composable
internal fun StableCleanEvent.render(
    shellSessions: AgentShellSessionRegistry?, expansion: HistoryExpansionBinding?, elapsed: Duration?,
) {
    CompositionLocalProvider(LocalHistoryExpansion provides expansion, LocalHistoryElapsed provides elapsed) {
        HistoryEventContent(this, MosaicHistoryContentRenderer(), shellSessions, elapsed)
    }
}

@Composable
public actual fun UnstableCleanEvent.render(shellSessions: AgentShellSessionRegistry?) {
    HistoryEventContent(this, MosaicHistoryContentRenderer(), shellSessions)
}

@Stable
internal class HistoryExpansionBinding(val expanded: () -> Boolean, val toggle: () -> Unit)
internal val LocalHistoryExpansion = staticCompositionLocalOf<HistoryExpansionBinding?> { null }
internal val LocalHistoryElapsed = staticCompositionLocalOf<Duration?> { null }

@Composable
internal fun ToolEvent(
    summary: String, rawName: String?, status: String, expansionKey: Any,
    detailStyle: TextStyle = TextStyle.Unspecified, expandedSummary: String? = null,
    content: @Composable ToolEventDetailsScope.() -> Unit,
) {
    var localExpanded by remember(expansionKey) { mutableStateOf(false) }
    val externalExpansion = LocalHistoryExpansion.current
    val expanded = externalExpansion?.expanded?.invoke() ?: localExpanded
    val headerColor = toolHeaderColor(status)
    Column(Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = {
                if (externalExpansion == null) localExpanded = !localExpanded else externalExpansion.toggle()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            val style = tuiInteractionTextStyle(hovered = isHovered, pressed = isPressed)
            if (expanded && expandedSummary != null) {
                WrappedHistoryText(
                    "v $expandedSummary" + LocalHistoryElapsed.current?.historyElapsedSuffix().orEmpty(),
                    color = headerColor, textStyle = style,
                )
            } else {
                HistoryItemHeader(
                    "${if (expanded) "v" else ">"} $summary",
                    modifier = Modifier.fillMaxWidth(), color = headerColor, textStyle = style,
                )
            }
        }
        if (expanded) {
            rawName?.takeIf(String::isNotBlank)?.let { WrappedHistoryText("Tool: $it", detailStyle) }
            ToolEventDetailsScope(detailStyle).content()
        }
    }
}

@Composable
private fun toolHeaderColor(status: String): Color = when (status) {
    "failed" -> TuiTheme.colorScheme.error
    "running", "streaming", "starting", "in_progress", "inprogress" -> TuiTheme.colorScheme.success
    else -> TuiTheme.colorScheme.onBackground
}

internal class ToolEventDetailsScope(private val detailStyle: TextStyle) {
    @Composable
    internal fun section(label: String, content: @Composable () -> Unit) {
        val expandedState = remember(label) { mutableStateOf(false) }
        val expanded by expandedState
        TuiPressable(
            onClick = { expandedState.value = !expanded }, modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            WrappedHistoryText(
                "${if (expanded) "v" else ">"} $label",
                textStyle = tuiInteractionTextStyle(
                    hovered = isHovered, pressed = isPressed, idleTextStyle = detailStyle,
                ),
            )
        }
        ToolDetailBody(expandedState, content)
    }
}

@Composable
private fun ToolDetailBody(expandedState: State<Boolean>, content: @Composable () -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        // Retain the measure slot while collapsed; expansion can add wrapped content later.
        val expanded = expandedState.value
        val placeable = subcompose(ToolDetailBodySlot(expanded)) {
            Column(Modifier.fillMaxWidth()) { if (expanded) content() }
        }.single().measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
        layout(
            constraints.constrainWidth(placeable.width),
            if (expanded) constraints.constrainHeight(placeable.height) else 0,
        ) { if (expanded) placeable.place(0, 0) }
    }
}

private data class ToolDetailBodySlot(val expanded: Boolean)

@Composable
internal fun ExpandableHistoryEvent(
    header: String, expansionKey: Any, headerStyle: TextStyle, content: @Composable () -> Unit,
) {
    var localExpanded by remember(expansionKey) { mutableStateOf(false) }
    val externalExpansion = LocalHistoryExpansion.current
    val expanded = externalExpansion?.expanded?.invoke() ?: localExpanded
    Column(Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = {
                if (externalExpansion == null) localExpanded = !localExpanded else externalExpansion.toggle()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            HistoryItemHeader(
                "${if (expanded) "v" else ">"} $header", modifier = Modifier.fillMaxWidth(),
                textStyle = tuiInteractionTextStyle(
                    hovered = isHovered, pressed = isPressed, idleTextStyle = headerStyle,
                ),
            )
        }
        if (expanded) content()
    }
}

@Composable
internal fun HistoryItemHeader(
    value: String, elapsed: Duration? = LocalHistoryElapsed.current, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, textStyle: TextStyle = TextStyle.Unspecified,
) {
    if (elapsed == null) {
        EllipsizedText(value, modifier, color = color, textStyle = textStyle)
    } else {
        EllipsizedTextWithTrailingContent(value, modifier, color = color, textStyle = textStyle) {
            Text(elapsed.historyElapsedSuffix(), color = color, textStyle = textStyle + TextStyle.Dim)
        }
    }
}
