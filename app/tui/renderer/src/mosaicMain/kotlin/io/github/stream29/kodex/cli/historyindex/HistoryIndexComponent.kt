@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.cli.components.EllipsizedText
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupMenuState
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiPopupMenuState
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle

@Stable
public actual class HistoryIndexInteractionRequest actual constructor(
    public actual val side: HistoryIndexSide,
    public actual val viewModel: HistoryIndexViewModel,
    public actual val generation: Long,
    public actual val index: Int,
    public actual val anchor: TuiPopupAnchor,
    public actual val revision: Long,
) {
    internal val sharedTarget = HistoryIndexTarget(viewModel, generation, index, revision)

    public actual fun isCurrent(): Boolean = sharedTarget.isCurrent()
}

@Composable
internal fun HistoryIndexSidebarBody(
    viewModel: HistoryIndexViewModel,
    columns: Int,
    rows: Int,
    side: HistoryIndexSide = HistoryIndexSide.Left,
    onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onOpenMenu: (HistoryIndexMenuRequest) -> Unit = {},
    listState: LazyListState = remember(viewModel) { LazyListState().apply { requestScrollToEnd() } },
    navigation: HistoryIndexNavigation? = null,
) {
    HistoryIndexContent(
        viewModel, side,
        MosaicHistoryIndexRenderer(columns, rows, listState, onHoverChanged = onHoverChanged, onOpenMenu = onOpenMenu),
        navigation,
    )
}

@Composable
internal fun MosaicHistoryIndexRowText(
    presentation: HistoryIndexRowPresentation, hovered: Boolean, pressed: Boolean, graphOverride: String? = null,
) {
    val graph = graphOverride ?: when (presentation.position) {
        HistoryIndexRowPosition.Single -> "●"
        HistoryIndexRowPosition.First -> "┌●"
        HistoryIndexRowPosition.Middle -> "├●"
        HistoryIndexRowPosition.Last -> "└●"
    }
    EllipsizedText(
        value = "$graph ${presentation.summary}",
        modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainer),
        color = TuiTheme.colorScheme.onSurface,
        textStyle = tuiInteractionTextStyle(hovered = hovered, pressed = pressed, idleTextStyle = TextStyle.Dim),
    )
}

@Composable
internal fun BoxScope.HistoryIndexContextMenuPopup(
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    index: Int,
    timestamp: String?,
    actions: List<HistoryIndexMenuAction>,
    onDismissRequest: () -> Unit,
    state: TuiPopupMenuState = rememberTuiPopupMenuState(),
) {
    TuiContextMenu(
        expanded = true, anchor = anchor, clickPosition = clickPosition,
        onDismissRequest = onDismissRequest, backgroundColor = TuiTheme.colorScheme.surfaceContainer, state = state,
    ) {
        TuiPopupMenuItem(key = "history-index-information", onClick = {}, enabled = false) {
            Column {
                Text("Index: $index")
                if (timestamp != null) Text("Timestamp: $timestamp")
            }
        }
        actions.forEach { action ->
            TuiPopupMenuItem(key = action.key, onClick = action.onClick, dismissOnClick = false) { Text(action.label) }
        }
    }
}
