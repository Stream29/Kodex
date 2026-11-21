package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.ui.compose.RowEdgePopupPosition
import kotlin.test.assertEquals

val sidebarHoverPopupPositionTest by testSuite {
    val popup = IntSize(160, 120)

    test("positions absolutely on the center-facing side, aligned to row top") {
        val row = Rect(200f, 40f, 330f, 72f)
        assertEquals(IntOffset(334, 40), position(row, true, IntSize(700, 300), popup))
        assertEquals(IntOffset(36, 40), position(row, false, IntSize(700, 300), popup))
    }

    test("flips horizontally and clamps against the viewport bottom") {
        val narrow = IntSize(420, 200)
        assertEquals(IntOffset(36, 80), position(Rect(200f, 180f, 330f, 212f), true, narrow, popup))
        assertEquals(IntOffset(154, 80), position(Rect(10f, 180f, 150f, 212f), false, narrow, popup))
    }

    test("reflows wide content if neither side fits and clamps to nonnegative coordinates") {
        val row = Rect(55f, -8f, 145f, 24f)
        val window = IntSize(230, 80)
        val maximum = RowEdgePopupPosition(row, preferAfter = false, gap = 4).maximumWidth(window.width)
        assertEquals(81, maximum)
        assertEquals(IntOffset(149, 0), position(row, false, window, IntSize(maximum, popup.height)))
        assertEquals(IntOffset.Zero, position(row, true, IntSize(100, 80), popup))
    }
}

private fun position(row: Rect, preferAfter: Boolean, window: IntSize, popup: IntSize): IntOffset =
    RowEdgePopupPosition(row, preferAfter, gap = 4).calculatePosition(IntRect.Zero, window, LayoutDirection.Rtl, popup)
