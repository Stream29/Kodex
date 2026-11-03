package io.github.stream29.kodex.app.ui.compose

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.Key
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val runtimeCascadeGeometryTest by testSuite {
    test("focus wraps and reveal scroll only moves when outside viewport") {
        assertEquals(2, RuntimeCascadeGeometry.focusIndex(3, 0, Key.Tab, true))
        assertEquals(0, RuntimeCascadeGeometry.focusIndex(3, 2, Key.DirectionDown, false))
        assertEquals(null, RuntimeCascadeGeometry.focusIndex(0, 0, Key.MoveHome, false))
        assertEquals(0, RuntimeCascadeGeometry.scrollToReveal(IntRect(0, 0, 100, 48), 200, 100))
        assertEquals(188, RuntimeCascadeGeometry.scrollToReveal(IntRect(0, 240, 100, 288), 0, 100))
        assertEquals(200, RuntimeCascadeGeometry.scrollToReveal(IntRect(0, 210, 100, 258), 200, 100))
    }
    test("root prefers above then below and clamps") {
        val window = IntSize(300, 200)
        val menu = IntSize(80, 60)
        assertEquals(IntOffset(20, 40), RuntimeCascadeGeometry.root(IntRect(20, 100, 50, 120), menu, window))
        assertEquals(IntOffset(20, 30), RuntimeCascadeGeometry.root(IntRect(20, 10, 50, 30), menu, window))
        assertEquals(IntOffset(220, 0), RuntimeCascadeGeometry.root(IntRect(290, 10, 300, 190), menu, window))
    }

    test("child flips left and aligns with parent row") {
        val window = IntSize(300, 200)
        val menu = IntSize(80, 60)
        assertEquals(
            IntOffset(150, 90),
            RuntimeCascadeGeometry.child(IntRect(70, 0, 150, 180), IntRect(70, 90, 150, 110), menu, window),
        )
        assertEquals(
            IntOffset(140, 140),
            RuntimeCascadeGeometry.child(IntRect(220, 0, 300, 180), IntRect(220, 175, 300, 195), menu, window),
        )
    }

    test("oversized child remains adjacent rather than becoming another page") {
        val position = RuntimeCascadeGeometry.child(
            IntRect(20, 0, 100, 100), IntRect(20, 30, 100, 50), IntSize(120, 150), IntSize(110, 100),
        )
        assertEquals(IntOffset(-100, 0), position)
    }
}
