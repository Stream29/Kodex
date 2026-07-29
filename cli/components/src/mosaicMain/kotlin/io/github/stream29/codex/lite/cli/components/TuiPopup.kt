package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.focus.focusTrap
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.modifier.composed
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Filler
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import io.github.stream29.codex.lite.cli.action.TuiActionScope
import io.github.stream29.codex.lite.cli.action.TuiShortcut
import io.github.stream29.codex.lite.cli.action.rememberTuiAction

/**
 * A stable handle that records the final surface bounds of one popup trigger.
 *
 * @property bounds `null` before the trigger is placed or after it leaves composition.
 */
@Stable
public class TuiPopupAnchor internal constructor() {
    internal var bounds: TuiPopupAnchorBounds? by mutableStateOf(null)

    internal fun update(coordinates: LayoutCoordinates) {
        val updatedBounds = TuiPopupAnchorBounds(
            position = coordinates.position,
            size = coordinates.size,
        )
        if (bounds != updatedBounds) bounds = updatedBounds
    }

    internal fun clear() {
        bounds = null
    }
}

/** Creates a [TuiPopupAnchor] that remains stable for this composition location. */
@Composable
public fun rememberTuiPopupAnchor(): TuiPopupAnchor = remember { TuiPopupAnchor() }

/** The final terminal-cell bounds reported by a [TuiPopupAnchor]. */
@Immutable
public data class TuiPopupAnchorBounds(
    public val position: IntOffset,
    public val size: IntSize,
)

/** Marks a trigger whose final surface position can be used by [TuiPopup]. */
public fun Modifier.tuiPopupAnchor(anchor: TuiPopupAnchor): Modifier = composed(
    fullyQualifiedName = "io.github.stream29.codex.lite.cli.components.tuiPopupAnchor",
    key1 = anchor,
) {
    DisposableEffect(anchor) {
        onDispose {
            anchor.clear()
        }
    }
    this.onPlaced(anchor::update)
}

/** Calculates a popup position from its trigger, host surface, and measured content. */
public fun interface TuiPopupPositionProvider {
    /** Maximum content size available around [anchorBounds] before positioning. */
    public fun calculateMaximumSize(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
    ): IntSize = surfaceSize

    public fun calculatePosition(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
        popupContentSize: IntSize,
    ): IntOffset

    public companion object {
        /** Places the popup above the trigger when possible, otherwise below it. */
        public val AboveStart: TuiPopupPositionProvider = object : TuiPopupPositionProvider {
            override fun calculateMaximumSize(
                anchorBounds: TuiPopupAnchorBounds,
                surfaceSize: IntSize,
            ): IntSize = IntSize(
                width = surfaceSize.width,
                height = maxOf(
                    anchorBounds.position.y,
                    surfaceSize.height - anchorBounds.position.y - anchorBounds.size.height,
                ).coerceAtLeast(0),
            )

            override fun calculatePosition(
                anchorBounds: TuiPopupAnchorBounds,
                surfaceSize: IntSize,
                popupContentSize: IntSize,
            ): IntOffset = calculateAboveStartPopupPosition(
                anchorBounds = anchorBounds,
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            )
        }

        /** Places a submenu beside the trigger, preferring its end side. */
        public val EndTop: TuiPopupPositionProvider = object : TuiPopupPositionProvider {
            override fun calculateMaximumSize(
                anchorBounds: TuiPopupAnchorBounds,
                surfaceSize: IntSize,
            ): IntSize = IntSize(
                width = maxOf(
                    anchorBounds.position.x,
                    surfaceSize.width - anchorBounds.position.x - anchorBounds.size.width,
                ).coerceAtLeast(0),
                height = surfaceSize.height,
            )

            override fun calculatePosition(
                anchorBounds: TuiPopupAnchorBounds,
                surfaceSize: IntSize,
                popupContentSize: IntSize,
            ): IntOffset = calculateEndTopPopupPosition(
                anchorBounds = anchorBounds,
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            )
        }
    }
}

/**
 * A bounded terminal surface that can host [TuiPopup] children.
 *
 * An anchor and its popup must be declared inside the same host. Declare persistent content before
 * popup children. A popup is rendered and hit-tested above its preceding siblings without
 * contributing to their layout size.
 */
@Composable
public fun TuiPopupHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = remember { TuiPopupHostState() }
    CompositionLocalProvider(LocalTuiPopupHost provides host) {
        Box(
            modifier = modifier.onPlaced(host::update),
            content = content,
        )
    }
}

/**
 * Renders [content] over this [TuiPopupHost] using [anchor] and [positionProvider].
 *
 * A primary-pointer press not handled by [content] invokes [onDismissRequest]. The popup must be
 * declared after the host's persistent content so it occupies the top interaction layer.
 *
 * @param onDismissRequest `null` keeps unhandled pointer presses available to the host content.
 */
@Composable
public fun BoxScope.TuiPopup(
    anchor: TuiPopupAnchor,
    onDismissRequest: (() -> Unit)? = null,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val host = requireTuiPopupHost()
    val hostPosition = host.position ?: return
    val anchorBounds = anchor.bounds ?: return
    val anchorBoundsInHost = TuiPopupAnchorBounds(
        position = anchorBounds.position - hostPosition,
        size = anchorBounds.size,
    )
    val latestOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val dismissPointerModifier = if (onDismissRequest == null) {
        Modifier
    } else {
        Modifier.onPointerEvent { event ->
            if (event.type == MouseEvent.Type.Press && event.button == MouseEvent.Button.Left) {
                latestOnDismissRequest.value?.invoke()
                true
            } else {
                false
            }
        }
    }

    Box(modifier = Modifier.matchParentSize() then dismissPointerModifier) {
        TuiPopupLayout(
            anchorBounds = anchorBoundsInHost,
            positionProvider = positionProvider,
            modifier = Modifier.matchParentSize(),
            popupModifier = modifier,
            content = content,
        )
    }
}

/**
 * Renders centered, modal [content] over this [TuiPopupHost].
 *
 * The dialog traps keyboard focus within its content, consumes pointer events that are not handled
 * by its content, and restores the prior focus when removed. An unhandled Escape invokes
 * [onDismissRequest]. A primary click outside [content] also invokes it when [dismissOnOutsideClick]
 * is `true`.
 *
 * The dialog clears every character cell inside its measured bounds before drawing [content].
 * Surface colors and other business styling remain the caller's responsibility through [modifier].
 */
@Composable
public fun BoxScope.TuiDialog(
    onDismissRequest: () -> Unit,
    dismissOnOutsideClick: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    requireTuiPopupHost()
    val latestOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val dismissAction = rememberTuiAction(
        id = "tui-dialog.dismiss",
        label = "Dismiss dialog",
        shortcut = TuiShortcut(key = "Escape"),
        onInvoke = { latestOnDismissRequest.value() },
    )
    val barrierModifier = Modifier.onPointerEvent { event ->
        if (
            dismissOnOutsideClick &&
            event.type == MouseEvent.Type.Press &&
            event.button == MouseEvent.Button.Left
        ) {
            latestOnDismissRequest.value()
        }
        true
    }

    Box(modifier = Modifier.matchParentSize() then barrierModifier) {
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.Center,
        ) {
            TuiActionScope(
                actions = listOf(dismissAction),
                blockParentActions = true,
            ) {
                Box(
                    modifier = modifier
                        .focusTrap()
                        .onPointerEvent { true },
                ) {
                    Filler(
                        char = ' ',
                        modifier = Modifier.matchParentSize(),
                        textStyle = TextStyle.Empty,
                    )
                    content()
                }
            }
        }
    }
}

/** @property position `null` until the host has been placed in the terminal surface. */
@Stable
private class TuiPopupHostState {
    var position: IntOffset? by mutableStateOf(null)
        private set

    fun update(coordinates: LayoutCoordinates) {
        if (position != coordinates.position) position = coordinates.position
    }
}

private val LocalTuiPopupHost = staticCompositionLocalOf<TuiPopupHostState> {
    error("TUI overlays must be composed inside TuiPopupHost.")
}

@Composable
private fun requireTuiPopupHost(): TuiPopupHostState = LocalTuiPopupHost.current

@Composable
private fun TuiPopupLayout(
    anchorBounds: TuiPopupAnchorBounds,
    positionProvider: TuiPopupPositionProvider,
    modifier: Modifier,
    popupModifier: Modifier,
    content: @Composable () -> Unit,
) {
    val measurePolicy = remember(anchorBounds, positionProvider) {
        MeasurePolicy { measurables, constraints ->
            check(measurables.size == 1) {
                "TuiPopupLayout requires exactly one popup content root."
            }
            check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                "TuiPopupHost must be measured with bounded width and height."
            }

            val surfaceSize = IntSize(constraints.maxWidth, constraints.maxHeight)
            val maximumSize = positionProvider.calculateMaximumSize(
                anchorBounds = anchorBounds,
                surfaceSize = surfaceSize,
            )
            val popup = measurables.single().measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = maximumSize.width.coerceIn(0, constraints.maxWidth),
                    maxHeight = maximumSize.height.coerceIn(0, constraints.maxHeight),
                ),
            )
            val position = positionProvider.calculatePosition(
                anchorBounds = anchorBounds,
                surfaceSize = surfaceSize,
                popupContentSize = IntSize(popup.width, popup.height),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                popup.place(position)
            }
        }
    }

    Layout(
        content = {
            Box(modifier = popupModifier) {
                content()
            }
        },
        modifier = modifier,
        debugInfo = { "TuiPopupLayout()" },
        measurePolicy = measurePolicy,
    )
}

private fun calculateAboveStartPopupPosition(
    anchorBounds: TuiPopupAnchorBounds,
    surfaceSize: IntSize,
    popupContentSize: IntSize,
): IntOffset {
    val maximumX = (surfaceSize.width - popupContentSize.width).coerceAtLeast(0)
    val maximumY = (surfaceSize.height - popupContentSize.height).coerceAtLeast(0)
    val aboveY = anchorBounds.position.y - popupContentSize.height
    val belowY = anchorBounds.position.y + anchorBounds.size.height
    val preferredY = when {
        aboveY >= 0 -> aboveY
        belowY <= maximumY -> belowY
        else -> aboveY
    }
    return IntOffset(
        x = anchorBounds.position.x.coerceIn(0, maximumX),
        y = preferredY.coerceIn(0, maximumY),
    )
}

private fun calculateEndTopPopupPosition(
    anchorBounds: TuiPopupAnchorBounds,
    surfaceSize: IntSize,
    popupContentSize: IntSize,
): IntOffset {
    val maximumX = (surfaceSize.width - popupContentSize.width).coerceAtLeast(0)
    val maximumY = (surfaceSize.height - popupContentSize.height).coerceAtLeast(0)
    val endX = anchorBounds.position.x + anchorBounds.size.width
    val startX = anchorBounds.position.x - popupContentSize.width
    val preferredX = when {
        endX <= maximumX -> endX
        startX >= 0 -> startX
        else -> endX
    }
    return IntOffset(
        x = preferredX.coerceIn(0, maximumX),
        y = anchorBounds.position.y.coerceIn(0, maximumY),
    )
}
