package io.github.stream29.kodex.utils.terminaltext

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val terminalTextTest by testSuite {
    test("calculates terminal widths") {
        assertEquals(4, "A你B".terminalCellWidth())
        assertEquals(1, "e\u0301".terminalCellWidth())
        assertEquals(2, "👩‍🔬".terminalCellWidth())
        assertEquals(0, "\u0301".terminalCellWidth())
    }

    test("exposes grapheme-preserving source and cell boundaries") {
        assertEquals(
            listOf(
                TerminalCellSegment(sourceStart = 0, sourceEnd = 2, cellWidth = 1),
                TerminalCellSegment(sourceStart = 2, sourceEnd = 7, cellWidth = 2),
            ),
            "e\u0301👩‍🔬".terminalCellSegments(),
        )
    }

    test("takes prefixes without splitting grapheme clusters") {
        assertEquals("A你", "A你B".takeFirstFittingTerminalWidth(3))
        assertEquals("e\u0301", "e\u0301x".takeFirstFittingTerminalWidth(1))
        assertEquals("👩‍🔬", "👩‍🔬!".takeFirstFittingTerminalWidth(2))
        assertEquals("\u0301A", "\u0301A".takeFirstFittingTerminalWidth(1))
        assertEquals("", "你".takeFirstFittingTerminalWidth(1))
        assertEquals("", "A".takeFirstFittingTerminalWidth(0))
    }

    test("takes suffixes without splitting grapheme clusters") {
        assertEquals("你B", "A你B".takeLastFittingTerminalWidth(3))
        assertEquals("e\u0301", "xe\u0301".takeLastFittingTerminalWidth(1))
        assertEquals("👩‍🔬", "!👩‍🔬".takeLastFittingTerminalWidth(2))
        assertEquals("", "你".takeLastFittingTerminalWidth(1))
        assertEquals("", "A".takeLastFittingTerminalWidth(0))
    }
}
