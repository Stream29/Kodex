@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement
import kotlin.time.Duration

@Composable
public actual fun rememberComposeHistoryItemRenderer(
    onOpenContextMenu: ((HistoryItemViewModel, storageIndex: Int, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
): HistoryItemRenderer {
    val base = rememberComposeHistoryContentRenderer()
    val callback = rememberUpdatedState(onOpenContextMenu)
    return remember(base) { ComposeHistoryItemRenderer(base, callback) }
}

private class ComposeHistoryItemRenderer(
    base: HistoryContentRenderer, private val contextMenu: State<((HistoryItemViewModel, Int, ApplicationHistoryMenuPlacement) -> Unit)?>,
) : HistoryItemRenderer, HistoryContentRenderer by base {
    @Composable override fun Loading() { Text("") }
    @Composable override fun Error() { Text("Error", color = MaterialTheme.colorScheme.error) }
    @Composable override fun ItemHeading(value: String, elapsed: Duration, style: HistoryTextStyle) {
        rememberComposeHistoryContentRenderer(elapsed).Heading(value, style)
    }
    @Composable override fun TurnMarker(duration: Duration) { HistoryTurnMarker(duration) }
    @Composable override fun Entry(item: HistoryItemViewModel, storageIndex: Int, content: @Composable () -> Unit) {
        val origin = remember(HistoryOriginKey(item)) { ComposeMenuOrigin() }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        Box(
            Modifier.fillMaxWidth().semantics { contentDescription = "History entry $storageIndex" }
                .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                    val open = contextMenu.value
                    if (open != null && event.buttons.isSecondaryPressed) origin.bounds?.let { bounds ->
                        // One row menu, not an additional SelectionContainer popup over it.
                        event.changes.forEach { it.consume() }
                        open(item, storageIndex, ComposeHistoryMenuPlacement(origin, bounds, event.changes.firstOrNull()?.position))
                    }
                }
                .onPreviewKeyEvent {
                    if (contextMenu.value != null && it.type == KeyEventType.KeyDown &&
                        (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)
                    ) {
                        origin.bounds?.let { bounds ->
                            contextMenu.value?.invoke(item, storageIndex, ComposeHistoryMenuPlacement(origin, bounds, null))
                        }
                        true
                    } else false
                }.focusable(enabled = contextMenu.value != null),
        ) { content() }
    }
    @Composable override fun GroupHeading(
        group: WorkGroupHistoryItemViewModel, value: String, elapsed: Duration, onClick: () -> Unit,
    ) {
        TextButton(
            onClick, Modifier.fillMaxWidth().semantics { contentDescription = "History group: $value" },
        ) { ItemHeading(value, elapsed, HistoryTextStyle.Normal) }
    }
    @Composable override fun Collapsed(summary: String, status: String, elapsed: Duration, onClick: () -> Unit) {
        TextButton(
            onClick, Modifier.fillMaxWidth().semantics { contentDescription = "Expand History item: $summary" },
        ) {
            Text(
                "> $summary", Modifier.weight(1f),
                color = when (status) {
                    "failed" -> MaterialTheme.colorScheme.error
                    "running", "streaming", "starting", "in_progress", "inprogress" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(elapsed.historyElapsedSuffix(), style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable override fun Event(
        event: StableCleanEvent, elapsed: Duration, onCollapse: (() -> Unit)?, shellSessions: AgentShellSessionRegistry?,
    ) {
        HistoryEventContent(
            event, rememberComposeHistoryContentRenderer(elapsed, onCollapse?.let { true }, onCollapse),
            shellSessions, elapsed,
        )
    }
}

private class HistoryOriginKey(private val item: HistoryItemViewModel) {
    override fun equals(other: Any?): Boolean = other is HistoryOriginKey && other.item === item
    override fun hashCode(): Int = 0
}
