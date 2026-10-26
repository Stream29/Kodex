package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.cli.components.*
import kotlin.time.Duration

internal class MosaicHistoryItemRenderer(
    private val rootItem: HistoryItemViewModel,
    private val generation: Long,
    private val focusRequester: FocusRequester?,
    private val onOpenContextMenu: ((Long, Int, HistoryItemViewModel, TuiPopupAnchor, IntOffset?) -> Unit)?,
) : HistoryItemRenderer, HistoryContentRenderer by MosaicHistoryContentRenderer() {
    @Composable override fun Loading() { Text("") }
    @Composable override fun Error() { Text("Error", color = TuiTheme.colorScheme.error) }
    @Composable override fun ItemHeading(value: String, elapsed: Duration, style: HistoryTextStyle) {
        HistoryItemHeader(value, elapsed, Modifier.fillMaxWidth(), textStyle = style.mosaicStyle())
    }
    @Composable override fun TurnMarker(duration: Duration) { HistoryTurnTimeMarkerRow(duration) }
    @Composable override fun Entry(item: HistoryItemViewModel, storageIndex: Int, content: @Composable () -> Unit) {
        val anchor = rememberTuiPopupAnchor()
        TuiPressable(
            onClick = {}, focusRequester = focusRequester.takeIf { item === rootItem },
            onSecondaryClick = onOpenContextMenu?.let { open ->
                { position -> open(generation, storageIndex, item, anchor, position) }
            },
            modifier = Modifier.fillMaxWidth().tuiPopupAnchor(anchor),
        ) { _, _, _ -> content() }
    }
    @Composable override fun GroupHeading(
        group: WorkGroupHistoryItemViewModel, value: String, elapsed: Duration, onClick: () -> Unit,
    ) {
        TuiPressable(
            onClick, Modifier.fillMaxWidth(), focusRequester = focusRequester.takeIf { group === rootItem },
        ) { _, hovered, pressed ->
            HistoryItemHeader(
                value, elapsed, Modifier.fillMaxWidth(),
                textStyle = tuiInteractionTextStyle(hovered = hovered, pressed = pressed),
            )
        }
    }
    @Composable override fun Collapsed(summary: String, status: String, elapsed: Duration, onClick: () -> Unit) {
        CollapsedToolHistoryRow(summary, status, elapsed, onClick)
    }
    @Composable override fun Event(
        event: StableCleanEvent, elapsed: Duration, onCollapse: (() -> Unit)?, shellSessions: AgentShellSessionRegistry?,
    ) {
        event.render(shellSessions, onCollapse?.let { HistoryExpansionBinding({ true }, it) }, elapsed)
    }
}
