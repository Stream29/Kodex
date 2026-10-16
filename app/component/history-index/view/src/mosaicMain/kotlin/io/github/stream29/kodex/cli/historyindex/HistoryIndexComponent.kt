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

/** Placement policy only; does not identify or own an Agent. */
public enum class HistoryIndexSide { Left, Right }

/** Renderer-local anchor/pointer and captured child target; host owns popup arbitration/grace. */
@Stable
public class HistoryIndexInteractionRequest(
    public val side: HistoryIndexSide,
    public val viewModel: HistoryIndexViewModel,
    public val generation: Long,
    public val index: Int,
    public val anchor: TuiPopupAnchor,
    public val revision: Long = viewModel.window.value.revision,
) {
    public var pointerPosition: IntOffset? by mutableStateOf(null)

    /** No latest selected-Agent lookup. Host separately checks its selected stable child identity. */
    public fun isCurrent(): Boolean = viewModel.window.value.revision == revision &&
        viewModel.contains(generation, index)
}

/** Identity (not data equality) distinguishes fresh menu openings for the same row. */
public class HistoryIndexMenuRequest(
    public val target: HistoryIndexInteractionRequest,
    public val clickPosition: IntOffset?,
)

/**
 * Complete index body, borrowing one stable child. Each composed row owns/releases its read
 * handle. Two bodies share window authority but have independent viewport/interaction lifetimes.
 * Pointer/keyboard upward scroll disables follow-latest; reaching the end restores it.
 */
@Composable
public fun HistoryIndexSidebarBody(
    viewModel: HistoryIndexViewModel,
    columns: Int,
    rows: Int,
    side: HistoryIndexSide = HistoryIndexSide.Left,
    onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onOpenMenu: (HistoryIndexMenuRequest) -> Unit = {},
    listState: LazyListState = remember(viewModel) { LazyListState().apply { requestScrollToEnd() } },
) {
    val window by viewModel.window.collectAsState()
    val followsLatest = remember(viewModel) { mutableStateOf(true) }
    val interactionSource = remember(viewModel, listState) {
        MutableScrollInteractionSource { interaction ->
            if (interaction.orientation == ScrollOrientation.Vertical &&
                (interaction.source == ScrollInputSource.Pointer || interaction.source == ScrollInputSource.Keyboard)
            ) {
                if (interaction.consumedDelta < 0) followsLatest.value = false
                else if (!listState.canScrollForward) followsLatest.value = true
            }
        }
    }
    LaunchedEffect(viewModel, listState) {
        snapshotFlow { listState.canScrollForward }.collect { forward ->
            if (!forward) followsLatest.value = true
        }
    }
    LaunchedEffect(viewModel, window.generation, window.revision, window.indexes.lastOrNull(), followsLatest.value) {
        if (followsLatest.value) listState.requestScrollToEnd()
    }
    LazyColumn(
        modifier = Modifier.width(columns.coerceAtLeast(0)).height(rows.coerceAtLeast(0)),
        state = listState,
        interactionSource = interactionSource,
    ) {
        items(window.indexes, key = { HistoryIndexRowKey(window.generation, window.revision, it) }) { index ->
            HistoryIndexSidebarRow(
                viewModel, window.generation, window.revision, index,
                historyIndexGraph(window.indexes.binarySearch(index), window.indexes.size),
                side, onHoverChanged, onOpenMenu,
            )
        }
    }
}

@Composable
private fun HistoryIndexSidebarRow(
    viewModel: HistoryIndexViewModel,
    generation: Long,
    revision: Long,
    index: Int,
    graph: String,
    side: HistoryIndexSide,
    onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit,
    onOpenMenu: (HistoryIndexMenuRequest) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    val request = remember(side, viewModel, generation, revision, index, anchor) {
        HistoryIndexInteractionRequest(side, viewModel, generation, index, anchor, revision)
    }
    val handle = remember(viewModel, generation, revision, index) { viewModel.acquireRow(generation, index) }
    DisposableEffect(request, handle) {
        onDispose {
            handle.release()
            onHoverChanged(request, false)
        }
    }
    val state by handle.state.collectAsState()
    TuiPressable(
        onClick = {},
        onSecondaryClick = { position ->
            if (request.isCurrent()) onOpenMenu(HistoryIndexMenuRequest(request, position))
        },
        modifier = Modifier.fillMaxWidth().tuiPopupAnchor(anchor)
            .onPointerEvent { event ->
                if (event.type == MouseEvent.Type.Motion) request.pointerPosition = event.position
                false
            }
            .onPointerHover(
                onPointerEnter = { if (request.isCurrent()) onHoverChanged(request, true) },
                onPointerExit = { onHoverChanged(request, false) },
            ),
    ) { _, hovered, pressed ->
        HistoryIndexRowContent(state, graph, hovered, pressed)
    }
}

/** Pure row rendering entrypoint, also useful for renderer snapshot coverage. */
@Composable
public fun HistoryIndexRowContent(
    state: HistoryIndexReadState<HistoryIndexEntry>,
    graph: String,
    hovered: Boolean = false,
    pressed: Boolean = false,
) {
    if (state == HistoryIndexReadState.Closed) return
    val label = when (state) {
        HistoryIndexReadState.Loading -> "…"
        HistoryIndexReadState.Failed -> "[error]"
        HistoryIndexReadState.Closed -> return
        is HistoryIndexReadState.Ready -> state.value.summary
    }
    EllipsizedText(
        value = "$graph $label",
        modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainer),
        color = if (state == HistoryIndexReadState.Failed) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
        textStyle = tuiInteractionTextStyle(hovered = hovered, pressed = pressed, idleTextStyle = TextStyle.Dim),
    )
}

/**
 * Delays hover locally, then acquires/releases a detail handle for this exact request.
 * Host filters selected child and popup priority before supplying request; hover exit grace
 * remains host-local. Invalid/released target is hidden, never shown as a new target's failure.
 */
@Composable
public fun BoxScope.HistoryIndexHoverPopup(
    request: HistoryIndexInteractionRequest?,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit = {},
) {
    val current = request ?: return
    val window by current.viewModel.window.collectAsState()
    val valid = window.revision == current.revision && current.isCurrent() && current.anchor.isPlaced
    LaunchedEffect(current, valid) { if (!valid) onDismissRequest() }
    if (!valid || contentColumns <= 0 || contentRows <= 0) return
    var delayed by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current) {
        delay(300.milliseconds)
        delayed = true
    }
    if (!delayed) return
    val handle = remember(current) { current.viewModel.acquireDetail(current.generation, current.index) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    val state by handle.state.collectAsState()
    LaunchedEffect(current, state) {
        if (state == HistoryIndexReadState.Closed) onDismissRequest()
    }
    HistoryIndexHoverContent(
        current.anchor, current.side, current.pointerPosition, state,
        contentColumns, contentRows, onHoverChanged,
    )
}

/** Full hover rendering including shared questions/options/hidden answers and scrollable detail. */
@Composable
public fun BoxScope.HistoryIndexHoverContent(
    anchor: TuiPopupAnchor,
    side: HistoryIndexSide,
    pointerPosition: IntOffset?,
    state: HistoryIndexReadState<HistoryIndexEntryDetail>,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
) {
    if (contentColumns <= 0 || contentRows <= 0) return
    val title: String
    val content: String
    when (state) {
        HistoryIndexReadState.Loading, HistoryIndexReadState.Closed -> return
        HistoryIndexReadState.Failed -> {
            title = "Error"
            content = "Unable to read or decode the history entry."
        }
        is HistoryIndexReadState.Ready -> {
            title = state.value.kind.displayName
            content = state.value.content
        }
    }
    val questionRows = (state as? HistoryIndexReadState.Ready)?.value?.requestUserInput?.requestUserInputHistoryRows()
    HistoryIndexHoverSurface(
        anchor, side, pointerPosition, title, content, contentColumns, contentRows, questionRows,
        if (state == HistoryIndexReadState.Failed) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
        onHoverChanged,
    )
}

@Composable
private fun BoxScope.HistoryIndexHoverSurface(
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

/**
 * Exact menu opening owns a timestamp handle. Only Ready(non-null) displays timestamp.
 * Check out calls the child's captured-Agent port; it never obtains an Agent from the host.
 * Host supplies only selected-child-matching requests and arbitrates shared Shell/index popup.
 */
@Composable
public fun BoxScope.HistoryIndexContextMenu(
    request: HistoryIndexMenuRequest?,
    onDismissRequest: () -> Unit,
) {
    val current = request ?: return
    val target = current.target
    val window by target.viewModel.window.collectAsState()
    val valid = window.revision == target.revision && target.isCurrent() && target.anchor.isPlaced
    LaunchedEffect(current, valid) { if (!valid) onDismissRequest() }
    if (!valid) return
    val handle = remember(current) { target.viewModel.acquireTimestamp(target.generation, target.index) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    val state by handle.state.collectAsState()
    LaunchedEffect(current, state) { if (state == HistoryIndexReadState.Closed) onDismissRequest() }
    if (state == HistoryIndexReadState.Closed) return
    val timestamp = (state as? HistoryIndexReadState.Ready)?.value?.let {
        try {
            formatPopupTimestamp(it, TimeZone.currentSystemDefault())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
    HistoryIndexContextMenuPopup(
        target.anchor, current.clickPosition, target.index, onDismissRequest,
        onCheckOut = {
            if (handle.state.value != HistoryIndexReadState.Closed && target.isCurrent()) {
                target.viewModel.checkOut(target.generation, target.index)
            }
        },
        timestamp = timestamp,
    )
}

/** Information + Check out only; null click uses keyboard anchor, null timestamp omits its field. */
@Composable
public fun BoxScope.HistoryIndexContextMenuPopup(
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    index: Int,
    onDismissRequest: () -> Unit,
    onCheckOut: () -> Unit,
    timestamp: String? = null,
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

internal val HistoryIndexEntryKind.displayName: String
    get() = when (this) {
        HistoryIndexEntryKind.CompactionPoint -> "Compaction point"
        HistoryIndexEntryKind.UserMessage -> "User message"
        HistoryIndexEntryKind.AssistantMessage -> "Assistant message"
        HistoryIndexEntryKind.AssistantCommentary -> "Assistant commentary"
        HistoryIndexEntryKind.AssistantFinal -> "Assistant final"
        HistoryIndexEntryKind.DeveloperMessage -> "Developer message"
        HistoryIndexEntryKind.AgentMessage -> "Agent message"
        HistoryIndexEntryKind.RequestUserInput -> "Request user input"
        HistoryIndexEntryKind.SuggestSubagents -> "suggest subagents"
        HistoryIndexEntryKind.PlanUpdate -> "Plan update"
    }

internal fun historyIndexGraph(position: Int, size: Int): String = when {
    size <= 1 -> "●"
    position == 0 -> "┌●"
    position == size - 1 -> "└●"
    else -> "├●"
}

private data class HistoryIndexRowKey(val generation: Long, val revision: Long, val index: Int)

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
