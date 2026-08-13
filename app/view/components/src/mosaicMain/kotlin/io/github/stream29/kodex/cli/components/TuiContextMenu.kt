package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * Displays a context menu at the secondary activation position over the surrounding
 * [TuiPopupHost].
 *
 * [clickPosition] is local to [anchor]. Pointer activation starts the menu at that terminal cell,
 * while `null` starts it at the anchor's beginning for keyboard activation. The final position is
 * clamped to the popup host.
 */
@Composable
public fun BoxScope.TuiContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset? = null,
    state: TuiPopupMenuState = rememberTuiPopupMenuState(),
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    content: TuiPopupMenuScope.() -> Unit,
) {
    val positionProvider = remember(clickPosition) {
        TuiPopupPositionProvider { anchorBounds, surfaceSize, popupContentSize ->
            calculateContextMenuPosition(
                anchorPosition = anchorBounds.position,
                clickPosition = clickPosition,
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            )
        }
    }
    TuiPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        anchor = anchor,
        state = state,
        positionProvider = positionProvider,
        backgroundColor = backgroundColor,
        modifier = modifier,
        content = content,
    )
}

internal fun calculateContextMenuPosition(
    anchorPosition: IntOffset,
    clickPosition: IntOffset?,
    surfaceSize: IntSize,
    popupContentSize: IntSize,
): IntOffset {
    val requestedPosition = anchorPosition + (clickPosition ?: IntOffset.Zero)
    return IntOffset(
        x = requestedPosition.x.coerceIn(
            minimumValue = 0,
            maximumValue = (surfaceSize.width - popupContentSize.width).coerceAtLeast(0),
        ),
        y = requestedPosition.y.coerceIn(
            minimumValue = 0,
            maximumValue = (surfaceSize.height - popupContentSize.height).coerceAtLeast(0),
        ),
    )
}
