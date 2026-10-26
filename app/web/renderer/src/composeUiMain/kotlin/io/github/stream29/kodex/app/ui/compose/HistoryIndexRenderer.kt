@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.stream29.kodex.app.ui.compose.EditorPopup as Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.historyindex.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
public actual fun rememberComposeHistoryIndexRenderer(
    onHoverChanged: (HistoryIndexInteraction, Boolean) -> Unit,
    onOpenMenu: (HistoryIndexMenuOpening) -> Unit,
): HistoryIndexRenderer {
    val hover = rememberUpdatedState(onHoverChanged)
    val menu = rememberUpdatedState(onOpenMenu)
    return remember { ComposeHistoryIndexRenderer(hover, menu) }
}

private interface ComposeIndexPlacement : HistoryIndexInteraction {
    val side: HistoryIndexSide
    val bounds: Rect?
    val point: Offset?
}

private class ComposeIndexRowOrigin(
    override val target: HistoryIndexTarget, override val side: HistoryIndexSide,
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
) : ComposeIndexPlacement, HistoryIndexRowInteraction {
    override var bounds by mutableStateOf<Rect?>(null)
    override var point by mutableStateOf<Offset?>(null)
    var openingPoint: Offset? = null
    override val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() { menu.value(HistoryIndexMenuOpening(ComposeIndexMenuOrigin(this, openingPoint))) }
}

private class ComposeIndexMenuOrigin(
    private val row: ComposeIndexRowOrigin, override val point: Offset?,
) : ComposeIndexPlacement {
    override val target: HistoryIndexTarget get() = row.target
    override val side: HistoryIndexSide get() = row.side
    override val bounds: Rect? get() = row.bounds
    override val isPlaced: Boolean get() = row.isPlaced
}

private class ComposeHistoryIndexRenderer(
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
) : HistoryIndexRenderer {
    override val canShowHover: Boolean get() = true

    @Composable override fun List(
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, content: (HistoryIndexListScope) -> Unit,
    ) {
        val list = rememberLazyListState()
        var follows by remember(viewModel) { mutableStateOf(true) }
        LaunchedEffect(viewModel, list) {
            snapshotFlow { list.canScrollForward }.collect { if (!it) follows = true }
        }
        LaunchedEffect(viewModel, window.generation, window.revision, window.indexes.lastOrNull(), follows) {
            if (follows && window.indexes.isNotEmpty()) list.requestScrollToItem(window.indexes.lastIndex)
        }
        val gesture = remember(viewModel, list) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y != 0f) follows = !list.canScrollForward
                    return Offset.Zero
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().nestedScroll(gesture)) {
            content(ComposeHistoryIndexListScope(this))
        }
    }
    @Composable override fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction =
        remember(target, side) { ComposeIndexRowOrigin(target, side, hover, menu) }

    @Composable override fun Row(
        interaction: HistoryIndexRowInteraction, presentation: HistoryIndexRowPresentation,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    ) {
        val origin = interaction as ComposeIndexRowOrigin
        var hovered by remember(origin) { mutableStateOf(false) }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        TextButton(
            onClick = {},
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .semantics { contentDescription = "History index ${origin.target.index}" }
                .onPointerEvent(PointerEventType.Enter) {
                    hovered = true
                    origin.point = it.changes.firstOrNull()?.position
                    onHoverChanged(true)
                }
                .onPointerEvent(PointerEventType.Move) { origin.point = it.changes.firstOrNull()?.position }
                .onPointerEvent(PointerEventType.Exit) { hovered = false; onHoverChanged(false) }
                .onPointerEvent(PointerEventType.Press) {
                    if (it.buttons.isSecondaryPressed) {
                        origin.openingPoint = it.changes.firstOrNull()?.position
                        onOpenMenu()
                    }
                }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)) {
                        origin.openingPoint = null
                        onOpenMenu()
                        true
                    } else false
                },
        ) {
            Text(
                presentation.value, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (presentation.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hovered) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
    @Composable override fun Hover(
        interaction: HistoryIndexInteraction, presentation: HistoryIndexDetailPresentation, onHoverChanged: (Boolean) -> Unit,
    ) {
        val origin = interaction as ComposeIndexPlacement
        val bounds = origin.bounds ?: return
        val placement = remember(bounds, origin.side, origin.point) { PointerPopupPosition(bounds, origin.side == HistoryIndexSide.Left, origin.point) }
        Popup(popupPositionProvider = placement, properties = PopupProperties(focusable = false)) {
            Surface(
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 480.dp).heightIn(max = 360.dp)
                    .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
                    .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        presentation.title, fontWeight = FontWeight.Bold,
                        color = if (presentation.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        val questions = presentation.questionRows
                        if (questions == null) SelectionContainer { Text(presentation.content) }
                        else {
                            val renderer = rememberComposeHistoryFormRenderer()
                            questions.forEach { renderer.Row(it) }
                        }
                    }
                }
            }
        }
    }
    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, onDismiss: () -> Unit, onCheckOut: () -> Unit,
    ) {
        val origin = opening.interaction as ComposeIndexPlacement
        val bounds = origin.bounds ?: return
        val placement = remember(bounds, origin.side, origin.point) { PointerPopupPosition(bounds, origin.side == HistoryIndexSide.Left, origin.point) }
        val formatted = timestamp?.let {
            try {
                it.toLocalDateTime(TimeZone.currentSystemDefault()).toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
        Popup(
            popupPositionProvider = placement, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        ) {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.widthIn(max = 480.dp).padding(12.dp)) {
                    Text("Index: ${origin.target.index}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    formatted?.let { Text("Timestamp: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenuItem(text = { Text("Check out") }, onClick = { onDismiss(); onCheckOut() })
                }
            }
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
        }
    }
}

private class ComposeHistoryIndexListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : HistoryIndexListScope {
    override fun Rows(window: HistoryIndexWindow, content: @Composable (Int) -> Unit) {
        original.items(window.indexes.size, { HistoryIndexRowKey(window.generation, window.revision, window.indexes[it]) }) {
            content(window.indexes[it])
        }
    }
}

internal class PointerPopupPosition(
    private val bounds: Rect, private val preferAfter: Boolean, private val point: Offset?,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val pointer = bounds.topLeft + (point ?: Offset(0f, bounds.height - 1f))
        val after = (pointer.x + 4).roundToInt()
        val before = (pointer.x - popupContentSize.width - 4).roundToInt()
        val x = if (preferAfter) {
            if (after + popupContentSize.width <= windowSize.width) after else before
        } else if (before >= 0) before else after
        val below = (pointer.y + 4).roundToInt()
        val above = (pointer.y - popupContentSize.height - 4).roundToInt()
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (if (below + popupContentSize.height <= windowSize.height) below else above)
                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
