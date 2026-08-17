package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

val wrappedHistoryTextLayoutCacheTest by testSuite {
    test("reuses wrapping at the same width and replaces it after resize") {
        val cache = WrappedHistoryTextLayoutCache("abcdef")

        val widthThree = cache.linesFor(width = 3)
        assertEquals(listOf("abc", "def"), widthThree)
        assertSame(widthThree, cache.linesFor(width = 3))

        val widthTwo = cache.linesFor(width = 2)
        assertEquals(listOf("ab", "cd", "ef"), widthTwo)
        assertNotSame(widthThree, widthTwo)
        assertSame(widthTwo, cache.linesFor(width = 2))
    }
}
