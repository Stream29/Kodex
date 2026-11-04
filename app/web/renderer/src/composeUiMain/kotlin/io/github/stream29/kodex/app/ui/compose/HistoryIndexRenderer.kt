@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeHistoryIndexRenderer(hover, menu, RowMenuFocusRestoration(scope)) }
}

private interface ComposeIndexPlacement : HistoryIndexInteraction {
    val side: HistoryIndexSide
    val bounds: Rect?
    val point: Offset?
    val fallback: FocusRequester?
}

private class ComposeIndexRowOrigin(
    override val target: HistoryIndexTarget, override val side: HistoryIndexSide,
    override val fallback: FocusRequester?,
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
) : ComposeIndexPlacement, HistoryIndexRowInteraction {
    override var bounds by mutableStateOf<Rect?>(null)
    override var point by mutableStateOf<Offset?>(null)
    var openingPoint: Offset? = null
    override val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() { if (isPlaced && target.isCurrent()) menu.value(HistoryIndexMenuOpening(ComposeIndexMenuOrigin(this, openingPoint))) }
}

private class ComposeIndexMenuOrigin(
    private val row: ComposeIndexRowOrigin, override val point: Offset?,
) : ComposeIndexPlacement {
    override val target: HistoryIndexTarget get() = row.target
    override val side: HistoryIndexSide get() = row.side
    override val bounds: Rect? get() = row.bounds
    override val isPlaced: Boolean get() = row.isPlaced
    override val fallback: FocusRequester? get() = row.fallback
}

private val LocalIndexRowFocused = staticCompositionLocalOf<(() -> Unit)?> { null }

private class ComposeHistoryIndexRenderer(
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
    private val restoration: RowMenuFocusRestoration,
) : HistoryIndexRenderer {
    override val canShowHover: Boolean get() = true

    @Composable override fun List(
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, content: (HistoryIndexListScope) -> Unit,
    ) {
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
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
        CompositionLocalProvider(LocalIndexRowFocused provides {
            follows = false
            scope.launch {
                withFrameNanos { }
                follows = !list.canScrollForward
            }
        }) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().nestedScroll(gesture)) {
                content(ComposeHistoryIndexListScope(this))
            }
        }
    }
    @Composable override fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction {
        val fallback = LocalRowMenuFallback.current
        return remember(target, side, fallback) { ComposeIndexRowOrigin(target, side, fallback, hover, menu) }
    }

    @Composable override fun Row(
        interaction: HistoryIndexRowInteraction, presentation: HistoryIndexRowPresentation,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    ) {
        val origin = interaction as ComposeIndexRowOrigin
        val rowFocused = LocalIndexRowFocused.current
        val focus = remember(origin) { FocusRequester() }
        var focused by remember(origin) { mutableStateOf(false) }
        var hovered by remember(origin) { mutableStateOf(false) }
        val failed = presentation.value == HistoryIndexRowValue.Failed
        val label = when (val value = presentation.value) {
            HistoryIndexRowValue.Loading -> "…"
            HistoryIndexRowValue.Failed -> "[error]"
            is HistoryIndexRowValue.Ready -> value.summary
        }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .semantics {
                    contentDescription = "History index ${origin.target.index}"
                    stateDescription = when (presentation.value) {
                        HistoryIndexRowValue.Loading -> "Loading"
                        HistoryIndexRowValue.Failed -> "Read failed"
                        is HistoryIndexRowValue.Ready -> "Read only"
                    }
                }
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
                }.onFocusChanged { focused = it.isFocused; if (it.isFocused) rowFocused?.invoke() }.focusRequester(focus).focusable(), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = 8.dp, end = 6.dp).size(5.dp)
                .background(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape))
            Text(
                label, Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hovered) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
            )
            SemanticRowMenuAction("Open menu for history index ${origin.target.index}") {
                val focused = origin.isPlaced && try { focus.requestFocus() } catch (_: IllegalStateException) { false }
                if (!focused) false else {
                    origin.openingPoint = null
                    onOpenMenu()
                    true
                }
            }
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
                        presentation.title, style = MaterialTheme.typography.titleSmall,
                        color = if (presentation.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        val questions = presentation.questionRows
                        val plan = presentation.plan
                        when {
                            questions != null -> {
                                val renderer = rememberComposeHistoryFormRenderer()
                                questions.forEach { renderer.Row(it) }
                            }
                            plan != null -> ComposeHistoryPlanBody(plan)
                            else -> SelectionContainer { Text(presentation.content) }
                        }
                    }
                }
            }
        }
    }
    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, detail: HistoryIndexDetailPresentation?,
        onDismiss: () -> Unit, onCheckOut: () -> Unit,
    ) {
        val origin = opening.interaction as ComposeIndexPlacement
        val mayRestore = LocalSidebarFocusRestoreAllowed.current
        restoration.Track(opening, origin.fallback, mayRestore) {
            origin.isPlaced && origin.target.isCurrent() && origin.target.viewModel.window.value.revision == origin.target.revision
        }
        val bounds = origin.bounds ?: return
        val placement = remember(bounds, origin.side, origin.point) { PointerPopupPosition(bounds, origin.side == HistoryIndexSide.Left, origin.point) }
        val checkOut = remember(opening) { FocusRequester() }
        val scroll = remember(opening) { ScrollState(0) }
        var viewport by remember(opening) { mutableIntStateOf(1) }
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
            Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp) {
                Column(
                    Modifier.widthIn(min = 160.dp, max = 480.dp).heightIn(max = 480.dp).padding(8.dp)
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val direction = when (it.key) {
                                Key.PageUp -> -1
                                Key.PageDown -> 1
                                else -> return@onPreviewKeyEvent false
                            }
                            scroll.dispatchRawDelta((direction * viewport).toFloat())
                            true
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Index: ${origin.target.index}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    formatted?.let { Text("Timestamp: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    Column(Modifier.weight(1f, fill = false).onSizeChanged { viewport = it.height.coerceAtLeast(1) }.verticalScroll(scroll)) {
                        if (detail == null) Text("Loading history entry…", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text(detail.title, style = MaterialTheme.typography.titleSmall,
                                color = if (detail.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            val questions = detail.questionRows
                            val plan = detail.plan
                            when {
                                questions != null -> {
                                    val renderer = rememberComposeHistoryFormRenderer()
                                    questions.forEach { renderer.Row(it) }
                                }
                                plan != null -> ComposeHistoryPlanBody(plan)
                                else -> SelectionContainer { Text(detail.content, style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                    }
                    CompactTextButton({ onDismiss(); onCheckOut() }, Modifier.fillMaxWidth().focusRequester(checkOut)) {
                        Text("Check out", Modifier.weight(1f))
                    }
                }
            }
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
        }
        LaunchedEffect(checkOut) {
            repeat(3) {
                withFrameNanos { }
                if (try { checkOut.requestFocus() } catch (_: IllegalStateException) { false }) return@LaunchedEffect
            }
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
