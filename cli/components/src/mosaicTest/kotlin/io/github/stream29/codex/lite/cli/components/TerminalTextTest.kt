package io.github.stream29.codex.lite.cli.components

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val terminalTextTest by testSuite {
    test("wrap uses terminal-cell width") {
        assertEquals(listOf("a界", "b"), "a界b".wrapToTerminalWidth(width = 3))
        assertEquals(listOf("first", "", "second"), "first\n\nsecond".wrapToTerminalWidth(width = 20))
    }

    test("ellipsize reserves its suffix") {
        assertEquals("a...", "abcdef".ellipsizeToTerminalWidth(maximumWidth = 4))
        assertEquals("ab", "abcdef".ellipsizeToTerminalWidth(maximumWidth = 2))
    }
}
