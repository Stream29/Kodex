package io.github.stream29.kodex.app.ui.compose

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.Key

/**
 * Window-relative positions for one cascade. The caller measures the actual menu surfaces
 * and parent rows; this calculation does not constrain an oversized menu to the viewport.
 */
internal object RuntimeCascadeGeometry {
    fun scrollToReveal(row: IntRect, scroll: Int, viewportHeight: Int): Int = when {
        row.top < scroll -> row.top
        row.bottom > scroll + viewportHeight -> row.bottom - viewportHeight
        else -> scroll
    }.coerceAtLeast(0)

    fun focusIndex(count: Int, current: Int, key: Key, shift: Boolean): Int? {
        if (count == 0) return null
        return when (key) {
            Key.MoveHome -> 0
            Key.MoveEnd -> count - 1
            Key.DirectionUp -> (current + count - 1) % count
            Key.Tab -> (current + if (shift) count - 1 else 1) % count
            else -> (current + 1) % count
        }
    }

    fun root(anchor: IntRect, menu: IntSize, window: IntSize): IntOffset {
        val x = clampToWindow(anchor.left, menu.width, window.width)
        val above = anchor.top - menu.height
        val below = anchor.bottom
        val y = when {
            above >= 0 -> above
            below + menu.height <= window.height -> below
            else -> clampToWindow(above, menu.height, window.height)
        }
        return IntOffset(x, y)
    }

    fun child(parentMenu: IntRect, parentRow: IntRect, menu: IntSize, window: IntSize): IntOffset {
        val right = parentMenu.right
        val left = parentMenu.left - menu.width
        val x = when {
            right + menu.width <= window.width -> right
            left >= 0 -> left
            window.width - right >= parentMenu.left -> right
            else -> left
        }
        return IntOffset(x, clampToWindow(parentRow.top, menu.height, window.height))
    }

    private fun clampToWindow(position: Int, size: Int, available: Int): Int =
        position.coerceIn(0, (available - size).coerceAtLeast(0))
}
