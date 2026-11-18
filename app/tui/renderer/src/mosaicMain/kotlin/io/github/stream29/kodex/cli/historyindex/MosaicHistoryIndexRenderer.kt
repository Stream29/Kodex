package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.app.SessionSidebarSide
import io.github.stream29.kodex.cli.app.SidebarHoverPopupSurface
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRow
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

internal open class MosaicHistoryIndexOrigin(
    val request: HistoryIndexInteractionRequest, val clickPosition: IntOffset? = null,
) : HistoryIndexInteraction {
    override val target: HistoryIndexTarget get() = request.sharedTarget
    override val isPlaced: Boolean get() = request.anchor.isPlaced
}

private class MosaicHistoryIndexRowInteraction(
    request: HistoryIndexInteractionRequest,
    private val hover: State<(HistoryIndexInteractionRequest, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuRequest) -> Unit>,
) : MosaicHistoryIndexOrigin(request), HistoryIndexRowInteraction {
    var openingPosition: IntOffset? = null
    override fun emitHover(hovered: Boolean) { hover.value(request, hovered) }
    override fun emitMenu() { menu.value(HistoryIndexMenuRequest(request, openingPosition)) }
}

internal class MosaicHistoryIndexRenderer(
    private val columns: Int, private val rows: Int,
    private val listState: LazyListState? = null,
    private val onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    private val onOpenMenu: (HistoryIndexMenuRequest) -> Unit = {},
    private val popupScope: BoxScope? = null,
) : HistoryIndexRenderer {
    override val canShowHover: Boolean get() = columns > 0 && rows > 0

    @Composable override fun List(
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, hasParentRows: Boolean, content: (HistoryIndexListScope) -> Unit,
    ) {
        val list = checkNotNull(listState)
        val followsLatest = remember(viewModel) { mutableStateOf(true) }
        val interaction = remember(viewModel, list) {
            MutableScrollInteractionSource { input ->
                if (input.orientation == ScrollOrientation.Vertical &&
                    (input.source == ScrollInputSource.Pointer || input.source == ScrollInputSource.Keyboard)
                ) {
                    if (input.consumedDelta < 0) followsLatest.value = false
                    else if (!list.canScrollForward) followsLatest.value = true
                }
            }
        }
        LaunchedEffect(viewModel, list) {
            snapshotFlow { list.canScrollForward }.collect { forward -> if (!forward) followsLatest.value = true }
        }
        LaunchedEffect(viewModel, window.generation, window.revision, window.entries.lastOrNull()?.index, hasParentRows, followsLatest.value) {
            if (followsLatest.value) list.requestScrollToEnd()
        }
        LazyColumn(
            Modifier.width(columns.coerceAtLeast(0)).height(rows.coerceAtLeast(0)), state = list, interactionSource = interaction,
        ) { content(MosaicHistoryIndexListScope(this)) }
    }
    @Composable override fun ParentAction(anchor: HistoryIndexParentAnchor, onReturn: () -> Unit) {
        TuiPressable(onClick = onReturn, modifier = Modifier.fillMaxWidth()) { focused, hovered, pressed ->
            EllipsizedText(
                anchor.label, modifier = Modifier.fillMaxWidth(),
                color = TuiTheme.colorScheme.onSurface,
                textStyle = tuiInteractionTextStyle(selected = focused, hovered = hovered, pressed = pressed),
            )
        }
    }

    @Composable override fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction {
        val anchor = rememberTuiPopupAnchor()
        val request = remember(target, side, anchor) {
            HistoryIndexInteractionRequest(side, target.viewModel, target.generation, target.index, anchor, target.revision)
        }
        val hover = rememberUpdatedState(onHoverChanged)
        val menu = rememberUpdatedState(onOpenMenu)
        return remember(request) { MosaicHistoryIndexRowInteraction(request, hover, menu) }
    }
    @Composable override fun Row(
        interaction: HistoryIndexRowInteraction, presentation: HistoryIndexRowPresentation,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    ) {
        val origin = interaction as MosaicHistoryIndexRowInteraction
        val request = origin.request
        TuiPressable(
            onClick = {},
            onSecondaryClick = { position -> origin.openingPosition = position; onOpenMenu() },
            modifier = Modifier.fillMaxWidth().tuiPopupAnchor(request.anchor)
                .onPointerEvent { event ->
                    if (event.type == MouseEvent.Type.Motion) request.pointerPosition = event.position
                    false
                }
                .onPointerHover(
                    onPointerEnter = { onHoverChanged(true) }, onPointerExit = { onHoverChanged(false) },
                ),
        ) { _, hovered, pressed -> MosaicHistoryIndexRowText(presentation, hovered, pressed) }
    }
    @Composable override fun Hover(
        interaction: HistoryIndexInteraction, presentation: HistoryIndexDetailPresentation, onHoverChanged: (Boolean) -> Unit,
    ) {
        val request = (interaction as MosaicHistoryIndexOrigin).request
        val sidebarSide = if (request.side == HistoryIndexSide.Left) SessionSidebarSide.Left else SessionSidebarSide.Right
        with(checkNotNull(popupScope)) {
            SidebarHoverPopupSurface(
                anchor = request.anchor,
                side = sidebarSide,
                pointerPosition = request.pointerPosition,
                title = presentation.title,
                content = presentation.content,
                contentColumns = columns,
                contentRows = rows,
                requestUserInputRows = presentation.questionRows,
                titleColor = TuiTheme.colorScheme.onSurface,
                onHoverChanged = onHoverChanged,
            )
        }
    }
    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, detail: HistoryIndexDetailPresentation?,
        onDismiss: () -> Unit, onEnterChild: (() -> Unit)?, onCheckOut: () -> Unit,
    ) {
        val origin = opening.interaction as MosaicHistoryIndexOrigin
        var details by remember(opening) { mutableStateOf(false) }
        val menuState = remember(opening) { TuiPopupMenuState() }
        val formatted = timestamp?.let {
            try {
                formatPopupTimestamp(it, TimeZone.currentSystemDefault())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
        with(checkNotNull(popupScope)) {
            if (details) {
                HistoryIndexDetailsDialog(detail) { details = false }
            } else {
                HistoryIndexContextMenuPopup(
                    origin.request.anchor, origin.clickPosition, origin.target.index, onDismiss, onCheckOut, formatted,
                    onDetails = { details = true }, state = menuState, onEnterChild = onEnterChild,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.HistoryIndexDetailsDialog(detail: HistoryIndexDetailPresentation?, onBack: () -> Unit) {
    val terminal = LocalTerminalState.current.size
    val scroll = remember { ScrollState() }
    val width = (terminal.columns - 4).coerceIn(1, 80)
    val height = (terminal.rows - 4).coerceAtLeast(1)
    TuiDialog(
        onDismissRequest = onBack,
        modifier = Modifier.width(width).height(height).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxSize().scrollablePaging(scroll, { scroll.viewportSize })) {
            Text(
                detail?.title ?: "History entry",
                Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
                color = TuiTheme.colorScheme.onSurface,
            )
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
                val questions = detail?.questionRows
                when {
                    detail == null -> Text("Loading history entry…")
                    questions != null -> questions.forEach { RequestUserInputHistoryRow(it) }
                    else -> Text(detail.content)
                }
            }
            TuiButton("Back", autoFocus = true, onClick = onBack)
        }
    }
}

private class MosaicHistoryIndexListScope(private val original: LazyListScope) : HistoryIndexListScope {
    override fun ParentRow(anchor: HistoryIndexParentAnchor, content: @Composable () -> Unit) {
        original.item(key = anchor, content = content)
    }

    override fun Rows(window: HistoryIndexWindow, content: @Composable (Int) -> Unit) {
        original.items(
            window.entries,
            key = { HistoryIndexRowKey(window.generation, window.revision, it.index) },
        ) { entry -> content(entry.index) }
    }
}
