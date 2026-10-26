package io.github.stream29.kodex.app.ui

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*

val textInputCursorStateTest by testSuite {
    test("a disabled projection clears rejected fingerprints so a later identical edit is admitted") {
        val input = TextInputCursorState("kept")
        assertTrue(input.observe("lost", 4))
        assertEquals(4, input.project("kept", enabled = false))
        input.project("kept", enabled = true)
        assertTrue(input.observe("lost", 4))
        assertFalse(input.observe("lost", 4))
    }

    test("selection-only events do not write the draft even before the value echo renders") {
        val input = TextInputCursorState("old")
        assertEquals(3, input.project("old"))
        assertTrue(input.observe("new value", 9))
        assertFalse(input.observe("new value", 4))
        input.project("old")
        assertFalse(input.observe("new value", 2))
        assertEquals(2, input.project("new value"))
        assertFalse(input.observe("new value", 1))
    }

    test("an external reset is the value authority and only clamps the retained cursor") {
        val input = TextInputCursorState("long value")
        assertTrue(input.observe("longer value", 12))
        assertEquals(0, input.project(""))
        assertFalse(input.observe("", 0))
        assertTrue(input.observe("next", 4))
        assertEquals(4, input.project("next"))
    }

    test("equal text with UTF-16 cursor movement does not manufacture another edit") {
        val input = TextInputCursorState("A\uD83D\uDE00B")
        assertFalse(input.observe("A\uD83D\uDE00B", 1))
        assertEquals(1, input.project("A\uD83D\uDE00B"))
        assertTrue(input.observe("A\uD83D\uDE00BC", 5))
        assertEquals(5, input.project("A\uD83D\uDE00BC"))
    }
}
