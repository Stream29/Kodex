@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.focus.focusTrap
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.drawTextStyleOverlay
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.modifier.composed
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

@Stable
public actual class TuiPopupAnchor internal actual constructor() {
    internal var bounds: TuiPopupAnchorBounds? by mutableStateOf(null)

    /** Whether this anchor currently belongs to a placed layout node. */
    public actual val isPlaced: Boolean
        get() = bounds != null

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

@Composable
public actual fun rememberTuiPopupAnchor(): TuiPopupAnchor = remember { TuiPopupAnchor() }

public actual fun Modifier.tuiPopupAnchor(anchor: TuiPopupAnchor): Modifier = composed(
    fullyQualifiedName = "io.github.stream29.kodex.cli.components.tuiPopupAnchor",
    key1 = anchor,
) {
    DisposableEffect(anchor) {
        onDispose {
            anchor.clear()
        }
    }
    this.onPlaced(anchor::update)
}

@Composable
public actual fun TuiPopupHost(
    modifier: Modifier,
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

@Composable
public actual fun BoxScope.TuiPopup(
    anchor: TuiPopupAnchor,
    onDismissRequest: (() -> Unit)?,
    positionProvider: TuiPopupPositionProvider,
    modifier: Modifier,
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

@Composable
public actual fun BoxScope.TuiDialog(
    onDismissRequest: () -> Unit,
    dismissOnOutsideClick: Boolean,
    onEscapeRequest: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    requireTuiPopupHost()
    val latestOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val latestOnEscapeRequest = rememberUpdatedState(onEscapeRequest)
    val dismissOnEscapeModifier = Modifier.onPreviewKeyEvent { event ->
        if (event != Escape) return@onPreviewKeyEvent false
        latestOnEscapeRequest.value()
        true
    }
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
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawTextStyleOverlay(TextStyle.Dim),
        )
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = dismissOnEscapeModifier
                    .drawBehind {
                        drawRect(char = ' ', textStyle = TextStyle.Empty)
                    }
                    .then(modifier)
                    .focusTrap()
                    .onPointerEvent { true },
            ) {
                content()
            }
        }
    }
}

private val Escape: KeyEvent = KeyEvent("Escape")

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
