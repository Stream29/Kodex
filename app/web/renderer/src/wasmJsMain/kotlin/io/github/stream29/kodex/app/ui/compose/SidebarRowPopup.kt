@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.ui.compose.EditorPopup as Popup
import kotlin.math.roundToInt

internal interface ComposeSidebarMenuPlacement {
    val preferAfter: Boolean
    val bounds: Rect?
    val point: Offset?
    val fallback: FocusRequester?
    val isPlaced: Boolean
}

internal abstract class ComposeSidebarRowOrigin(
    final override val preferAfter: Boolean,
    final override val fallback: FocusRequester?,
) : ComposeSidebarMenuPlacement {
    final override var bounds: Rect? by mutableStateOf(null)
    var hoverBounds: Rect? by mutableStateOf(null)
    var openingPoint: Offset? = null
    // A row handle without an opening snapshot uses the row's keyboard anchor.
    final override val point: Offset? get() = null
    final override val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
}

@Composable
internal fun SidebarPopupRow(
    origin: ComposeSidebarRowOrigin,
    summary: String,
    rowDescription: String,
    rowStateDescription: String,
    menuActionDescription: String,
    boldOnHover: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onHoverChanged: (Boolean) -> Unit,
    onOpenMenu: () -> Unit,
) {
    val focus = remember(origin) { FocusRequester() }
    var focused by remember(origin) { mutableStateOf(false) }
    var hovered by remember(origin) { mutableStateOf(false) }
    DisposableEffect(origin) {
        onDispose {
            origin.bounds = null
            origin.hoverBounds = null
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
            .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
            .semantics {
                contentDescription = rowDescription
                stateDescription = rowStateDescription
            }
            .onPointerEvent(PointerEventType.Enter) {
                hovered = true
                onHoverChanged(true)
            }
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
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusRequester(focus)
            .focusable(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.padding(start = 8.dp, end = 6.dp).size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Text(
            summary,
            Modifier.weight(1f).padding(end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (boldOnHover && hovered) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
        )
        SemanticRowMenuAction(menuActionDescription) {
            val requested = origin.isPlaced && try { focus.requestFocus() } catch (_: IllegalStateException) { false }
            if (!requested) false else {
                origin.openingPoint = null
                onOpenMenu()
                true
            }
        }
    }
}

@Composable
internal fun SidebarHoverPopup(
    origin: ComposeSidebarRowOrigin,
    title: String,
    onHoverChanged: (Boolean) -> Unit,
    singleLineTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bounds = origin.hoverBounds ?: origin.bounds ?: return
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val placement = remember(bounds, origin.preferAfter, gap) {
        RowEdgePopupPosition(bounds, origin.preferAfter, gap)
    }
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val maxWidth = if (windowWidth > 0) {
        val available = placement.maximumWidth(windowWidth)
        if (available == 0) return
        with(density) { available.toDp() }.coerceAtMost(480.dp)
    } else 480.dp
    Popup(popupPositionProvider = placement, properties = PopupProperties(focusable = false)) {
        Surface(
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = maxWidth).heightIn(max = 360.dp)
                .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
                .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (singleLineTitle) 1 else Int.MAX_VALUE,
                    overflow = if (singleLineTitle) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
                content()
            }
        }
    }
}

@Composable
internal fun SidebarMenuPopup(
    opening: Any,
    origin: ComposeSidebarMenuPlacement,
    restoration: RowMenuFocusRestoration,
    isOriginValid: () -> Boolean,
    dismissWhenUnplaced: Boolean = false,
    columnModifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    actionLabel: String,
    actionColors: ButtonColors = ButtonDefaults.textButtonColors(),
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    afterAction: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val mayRestore = LocalSidebarFocusRestoreAllowed.current
    restoration.Track(opening, origin.fallback, mayRestore, isOriginValid)
    if (dismissWhenUnplaced) {
        LaunchedEffect(opening, origin.isPlaced) { if (!origin.isPlaced) onDismiss() }
        if (!origin.isPlaced) return
    }
    val bounds = origin.bounds ?: return
    val placement = remember(bounds, origin.preferAfter, origin.point) {
        PointerPopupPosition(bounds, origin.preferAfter, origin.point)
    }
    val actionFocus = remember(opening) { FocusRequester() }
    Popup(
        popupPositionProvider = placement,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
    ) {
        Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp) {
            Column(
                Modifier.widthIn(min = 160.dp, max = 480.dp).heightIn(max = 480.dp).padding(8.dp)
                    .then(columnModifier),
                verticalArrangement = verticalArrangement,
            ) {
                content()
                CompactTextButton(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().focusRequester(actionFocus),
                    colors = actionColors,
                ) { Text(actionLabel, Modifier.weight(1f)) }
                afterAction?.invoke(this)
            }
        }
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
    }
    LaunchedEffect(actionFocus) {
        repeat(3) {
            withFrameNanos { }
            if (try { actionFocus.requestFocus() } catch (_: IllegalStateException) { false }) return@LaunchedEffect
        }
    }
}

internal class RowEdgePopupPosition(
    bounds: Rect, private val preferAfter: Boolean, gap: Int,
) : PopupPositionProvider {
    private val afterEdge = bounds.right.roundToInt() + gap
    private val beforeEdge = bounds.left.roundToInt() - gap
    private val top = bounds.top.roundToInt()

    // Reflow an oversized preview into the larger side rather than covering the anchor row.
    fun maximumWidth(windowWidth: Int): Int = maxOf(beforeEdge, windowWidth - afterEdge)
        .coerceIn(0, windowWidth)

    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val before = beforeEdge - popupContentSize.width
        val x = if (preferAfter) {
            if (afterEdge <= maxX) afterEdge else before
        } else if (before >= 0) before else afterEdge
        return IntOffset(
            x.coerceIn(0, maxX),
            top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
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
