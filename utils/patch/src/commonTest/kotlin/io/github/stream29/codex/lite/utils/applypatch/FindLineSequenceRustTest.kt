package io.github.stream29.codex.lite.utils.applypatch

import kotlin.test.Test
import kotlin.test.assertEquals

class FindLineSequenceRustTest {
    @Test
    fun exactMatchFindsSequence() {
        assertEquals(1, findLineSequence(listOf("foo", "bar", "baz"), listOf("bar", "baz"), startIndex = 0, anchorAtEnd = false))
    }

    @Test
    fun rstripMatchIgnoresTrailingWhitespace() {
        assertEquals(0, findLineSequence(listOf("foo   ", "bar\t\t"), listOf("foo", "bar"), startIndex = 0, anchorAtEnd = false))
    }

    @Test
    fun trimMatchIgnoresLeadingAndTrailingWhitespace() {
        assertEquals(0, findLineSequence(listOf("    foo   ", "   bar\t"), listOf("foo", "bar"), startIndex = 0, anchorAtEnd = false))
    }

    @Test
    fun targetLinesLongerThanInputReturnsNull() {
        assertEquals(null, findLineSequence(listOf("just one line"), listOf("too", "many", "lines"), startIndex = 0, anchorAtEnd = false))
    }

    @Test
    fun startIndexSkipsEarlierMatches() {
        assertEquals(
            3,
            findLineSequence(
                listOf("a", "target", "x", "target", "x"),
                listOf("target", "x"),
                startIndex = 2,
                anchorAtEnd = false,
            ),
        )
    }

    @Test
    fun anchorAtEndOnlyMatchesTailPosition() {
        val lines = listOf("target", "x", "other", "target", "x")
        assertEquals(3, findLineSequence(lines, listOf("target", "x"), startIndex = 0, anchorAtEnd = true))
        assertEquals(null, findLineSequence(lines, listOf("other", "target"), startIndex = 0, anchorAtEnd = true))
    }

    @Test
    fun repeatedPrefixesDoNotConfuseMatcher() {
        assertEquals(
            2,
            findLineSequence(
                listOf("a", "b", "a", "b", "a", "c"),
                listOf("a", "b", "a", "c"),
                startIndex = 0,
                anchorAtEnd = false,
            ),
        )
    }

    @Test
    fun unicodePunctuationMatchNormalizesCommonDashCharacters() {
        assertEquals(
            0,
            findLineSequence(
                lines = listOf("import asyncio  # local import \u2013 avoids top\u2011level dep"),
                targetLines = listOf("import asyncio  # local import - avoids top-level dep"),
                startIndex = 0,
                anchorAtEnd = false,
            ),
        )
    }
}
