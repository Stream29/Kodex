package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.TuiPopupAnchorBounds
import kotlin.test.assertEquals

val sidebarHoverPopupPositionTest by testSuite {
    val left = SidebarHoverPopupPositionProvider(SessionSidebarSide.Left, 60, 20)
    val right = SidebarHoverPopupPositionProvider(SessionSidebarSide.Right, 60, 20)
    val popup = IntSize(15, 4)

    test("starts at row top on the center-facing side") {
        val anchor = TuiPopupAnchorBounds(IntOffset(20, 2), IntSize(10, 1))
        val surface = IntSize(80, 24)
        assertEquals(IntOffset(31, 2), left.calculatePosition(anchor, surface, popup))
        assertEquals(IntOffset(4, 2), right.calculatePosition(anchor, surface, popup))
    }

    test("flips horizontally and clamps against the terminal bottom") {
        val nearRight = TuiPopupAnchorBounds(IntOffset(20, 9), IntSize(10, 1))
        val nearLeft = TuiPopupAnchorBounds(IntOffset(2, 9), IntSize(10, 1))
        val narrow = IntSize(36, 10)
        assertEquals(IntOffset(4, 6), left.calculatePosition(nearRight, narrow, popup))
        assertEquals(IntOffset(13, 6), right.calculatePosition(nearLeft, narrow, popup))
    }

    test("clamps on both axes, keeping the first row free when possible") {
        val anchor = TuiPopupAnchorBounds(IntOffset(0, 0), IntSize(10, 1))
        assertEquals(IntOffset(0, 1), left.calculatePosition(anchor, IntSize(19, 10), popup))
        assertEquals(IntOffset(4, 1), right.calculatePosition(anchor, IntSize(19, 10), popup))
        assertEquals(IntOffset.Zero, left.calculatePosition(anchor, IntSize(9, 2), popup))
    }
}
