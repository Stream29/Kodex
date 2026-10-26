@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.heightIn
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.layout.widthIn
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadState
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.cli.components.EllipsizedText
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiPopup
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupAnchorBounds
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupPositionProvider
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRow
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowModel
import io.github.stream29.kodex.cli.history.requestUserInputHistoryRows
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Stable
public actual class HistoryIndexInteractionRequest actual constructor(
    public actual val side: HistoryIndexSide,
    public actual val viewModel: HistoryIndexViewModel,
    public actual val generation: Long,
    public actual val index: Int,
    public actual val anchor: TuiPopupAnchor,
    public actual val revision: Long,
) {
    public actual var pointerPosition: IntOffset? by mutableStateOf(null)
    internal val sharedTarget = HistoryIndexTarget(viewModel, generation, index, revision)

    public actual fun isCurrent(): Boolean = sharedTarget.isCurrent()
}

@Composable
public actual fun HistoryIndexSidebarBody(
    viewModel: HistoryIndexViewModel,
    columns: Int,
    rows: Int,
    side: HistoryIndexSide,
    onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit,
    onOpenMenu: (HistoryIndexMenuRequest) -> Unit,
    listState: LazyListState,
) {
    HistoryIndexContent(
        viewModel, side,
        MosaicHistoryIndexRenderer(columns, rows, listState, onHoverChanged = onHoverChanged, onOpenMenu = onOpenMenu),
    )
}

@Composable
public actual fun HistoryIndexRowContent(
    state: HistoryIndexReadState<HistoryIndexEntry>,
    graph: String,
    hovered: Boolean,
    pressed: Boolean,
) {
    val presentation = historyIndexRowPresentation(state, graph) ?: return
    MosaicHistoryIndexRowText(presentation, hovered, pressed)
}

@Composable
internal fun MosaicHistoryIndexRowText(presentation: HistoryIndexRowPresentation, hovered: Boolean, pressed: Boolean) {
    EllipsizedText(
        value = presentation.value,
        modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainer),
        color = if (presentation.failed) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
        textStyle = tuiInteractionTextStyle(hovered = hovered, pressed = pressed, idleTextStyle = TextStyle.Dim),
    )
}

@Composable
public actual fun BoxScope.HistoryIndexHoverPopup(
    request: HistoryIndexInteractionRequest?,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val origin = remember(request) { request?.let { MosaicHistoryIndexOrigin(it) } }
    HistoryIndexHoverContent(
        origin, MosaicHistoryIndexRenderer(contentColumns, contentRows, popupScope = this),
        onHoverChanged, onDismissRequest,
    )
}

@Composable
public actual fun BoxScope.HistoryIndexHoverContent(
    anchor: TuiPopupAnchor,
    side: HistoryIndexSide,
    pointerPosition: IntOffset?,
    state: HistoryIndexReadState<HistoryIndexEntryDetail>,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
) {
    if (contentColumns <= 0 || contentRows <= 0) return
    val presentation = historyIndexDetailPresentation(state) ?: return
    HistoryIndexHoverSurface(
        anchor, side, pointerPosition, presentation.title, presentation.content, contentColumns, contentRows, presentation.questionRows,
        if (presentation.failed) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
        onHoverChanged,
    )
}

@Composable
internal fun BoxScope.HistoryIndexHoverSurface(
    anchor: TuiPopupAnchor,
    side: HistoryIndexSide,
    pointerPosition: IntOffset?,
    title: String,
    content: String,
    contentColumns: Int,
    contentRows: Int,
    questionRows: List<RequestUserInputHistoryRowModel>?,
    titleColor: Color,
    onHoverChanged: (Boolean) -> Unit,
) {
    val popupWidth = maxOf(
        title.terminalCellWidth(),
        questionRows?.flatMap { it.value.lineSequence().toList() }?.maxOfOrNull(String::terminalCellWidth)
            ?: content.lineSequence().maxOfOrNull(String::terminalCellWidth) ?: 0,
    ).coerceIn(1, contentColumns)
    val lines = content.wrapToTerminalWidth(popupWidth)
    val bodyHeight = questionRows?.sumOf { it.value.wrapToTerminalWidth(popupWidth).size.coerceAtLeast(1) }
        ?: lines.size
    val popupHeight = (bodyHeight + 1).coerceIn(1, contentRows)
    val listState = remember(anchor, content, questionRows) { LazyListState() }
    TuiPopup(
        anchor = anchor,
        onDismissRequest = null,
        positionProvider = remember(side, pointerPosition, contentColumns, contentRows) {
            HistoryIndexHoverPositionProvider(side, contentColumns, contentRows, pointerPosition)
        },
        modifier = Modifier.widthIn(max = popupWidth).heightIn(max = popupHeight)
            .drawBehind { drawRect(char = ' ', textStyle = TextStyle.Empty) }
            .background(TuiTheme.colorScheme.surfaceContainer)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
    ) {
        Column(Modifier.width(popupWidth).height(popupHeight).background(TuiTheme.colorScheme.surfaceContainer)) {
            Text(
                title,
                modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
                color = titleColor,
            )
            if (popupHeight > 1) {
                LazyColumn(
                    modifier = Modifier.width(popupWidth).height(popupHeight - 1)
                        .background(TuiTheme.colorScheme.surfaceContainer),
                    state = listState,
                ) {
                    if (questionRows == null) {
                        items(lines) { Text(it, Modifier.fillMaxWidth(), color = TuiTheme.colorScheme.onSurface) }
                    } else {
                        items(questionRows) { RequestUserInputHistoryRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
public actual fun BoxScope.HistoryIndexContextMenu(
    request: HistoryIndexMenuRequest?,
    onDismissRequest: () -> Unit,
) {
    val opening = remember(request) {
        request?.let { HistoryIndexMenuOpening(MosaicHistoryIndexOrigin(it.target, it.clickPosition)) }
    }
    HistoryIndexMenuContent(
        opening, MosaicHistoryIndexRenderer(0, 0, popupScope = this), onDismissRequest,
    )
}

@Composable
public actual fun BoxScope.HistoryIndexContextMenuPopup(
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    index: Int,
    onDismissRequest: () -> Unit,
    onCheckOut: () -> Unit,
    timestamp: String?,
) {
    TuiContextMenu(
        expanded = true, anchor = anchor, clickPosition = clickPosition,
        onDismissRequest = onDismissRequest, backgroundColor = TuiTheme.colorScheme.surfaceContainer,
    ) {
        TuiPopupMenuItem(key = "history-index-information", onClick = {}, enabled = false) {
            Column {
                Text("Index: $index")
                if (timestamp != null) Text("Timestamp: $timestamp")
            }
        }
        TuiPopupMenuItem(key = "history-index-check-out", onClick = onCheckOut) { Text("Check out") }
    }
}

internal class HistoryIndexHoverPositionProvider(
    private val side: HistoryIndexSide,
    private val contentColumns: Int,
    private val contentRows: Int,
    private val pointerPosition: IntOffset?,
) : TuiPopupPositionProvider {
    override fun calculateMaximumSize(anchorBounds: TuiPopupAnchorBounds, surfaceSize: IntSize): IntSize =
        IntSize(contentColumns.coerceIn(0, surfaceSize.width), contentRows.coerceIn(0, surfaceSize.height))

    override fun calculatePosition(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
        popupContentSize: IntSize,
    ): IntOffset {
        val localPointer = pointerPosition ?: IntOffset(0, anchorBounds.size.height - 1)
        val pointer = anchorBounds.position + localPointer
        val after = pointer.x + 1
        val before = pointer.x - popupContentSize.width
        val requestedX = when (side) {
            HistoryIndexSide.Left -> if (after + popupContentSize.width <= surfaceSize.width) after else before
            HistoryIndexSide.Right -> if (before >= 0) before else after
        }
        val below = pointer.y + 1
        val above = pointer.y - popupContentSize.height
        val requestedY = if (below + popupContentSize.height <= surfaceSize.height) below else above
        val maxY = (surfaceSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(
            requestedX.coerceIn(0, (surfaceSize.width - popupContentSize.width).coerceAtLeast(0)),
            requestedY.coerceIn(1.coerceAtMost(maxY), maxY),
        )
    }
}
