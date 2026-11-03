package io.github.stream29.kodex.app.ui.compose

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val statusBarLayoutTest by testSuite {
    test("settings reserve first row and exact fit remains on it") {
        assertEquals(
            listOf(StatusItemPosition(0, 0), StatusItemPosition(28, 0), StatusItemPosition(0, 1)),
            statusItemPositions(listOf(20, 20, 30), 88, 32, 8),
        )
    }
    test("empty first row and ordered wrapping") {
        assertEquals(
            listOf(StatusItemPosition(0, 1), StatusItemPosition(0, 2), StatusItemPosition(18, 2)),
            statusItemPositions(listOf(70, 10, 10), 80, 32, 8),
        )
    }
    test("no settings uses full width and empty items are valid") {
        assertEquals(emptyList(), statusItemPositions(emptyList(), 80, 32, 8))
        assertEquals(
            listOf(StatusItemPosition(0, 0), StatusItemPosition(48, 0)),
            statusItemPositions(listOf(40, 32), 80, 0, 8),
        )
    }
}
