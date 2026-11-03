package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val agentPendingPanelBudgetTest by testSuite {
    test("two pending panels share the remaining rows instead of reserving one panel twice") {
        assertEquals(11, agentPendingToolRows(23, 2))
        assertEquals(12, agentPendingToolRows(24, 2))
        assertEquals(0, agentPendingToolRows(1, 2))
        assertEquals(1, agentPendingToolRows(2, 2))
    }
    test("zero and one panel preserve their previous allocation") {
        for (available in 0..60) {
            assertEquals(0, agentPendingToolRows(available, 0))
            assertEquals(minOf(available, 12), agentPendingToolRows(available, 1))
        }
    }
    test("all mounted panels fit without consuming the caller's reserved Composer row") {
        for (panels in 0..3) for (rows in 0..60) {
            val reservedComposer = minOf(rows, 1)
            val perPanel = agentPendingToolRows(rows - reservedComposer, panels)
            assertTrue(perPanel in 0..12)
            assertTrue(perPanel * panels + reservedComposer <= rows)
        }
    }
}
