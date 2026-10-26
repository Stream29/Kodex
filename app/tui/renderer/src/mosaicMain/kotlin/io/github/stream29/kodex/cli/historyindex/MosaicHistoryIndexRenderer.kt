package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.*
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.components.*
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
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, content: (HistoryIndexListScope) -> Unit,
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
        LaunchedEffect(viewModel, window.generation, window.revision, window.indexes.lastOrNull(), followsLatest.value) {
            if (followsLatest.value) list.requestScrollToEnd()
        }
        LazyColumn(
            Modifier.width(columns.coerceAtLeast(0)).height(rows.coerceAtLeast(0)), state = list, interactionSource = interaction,
        ) { content(MosaicHistoryIndexListScope(this)) }
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
        with(checkNotNull(popupScope)) {
            HistoryIndexHoverSurface(
                request.anchor, request.side, request.pointerPosition, presentation.title, presentation.content,
                columns, rows, presentation.questionRows,
                if (presentation.failed) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
                onHoverChanged,
            )
        }
    }
    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, onDismiss: () -> Unit, onCheckOut: () -> Unit,
    ) {
        val origin = opening.interaction as MosaicHistoryIndexOrigin
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
            HistoryIndexContextMenuPopup(origin.request.anchor, origin.clickPosition, origin.target.index, onDismiss, onCheckOut, formatted)
        }
    }
}

private class MosaicHistoryIndexListScope(private val original: LazyListScope) : HistoryIndexListScope {
    override fun Rows(window: HistoryIndexWindow, content: @Composable (Int) -> Unit) {
        original.items(window.indexes, key = { HistoryIndexRowKey(window.generation, window.revision, it) }) { content(it) }
    }
}
