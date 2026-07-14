package io.github.stream29.codex.lite.utils.applypatch

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals



val findLineSequenceRustTest by testSuite {
    test("exact match finds sequence") {
        assertEquals(1, findLineSequence(listOf("foo", "bar", "baz"), listOf("bar", "baz"), startIndex = 0, anchorAtEnd = false))
    }

    test("rstrip match ignores trailing whitespace") {
        assertEquals(0, findLineSequence(listOf("foo   ", "bar\t\t"), listOf("foo", "bar"), startIndex = 0, anchorAtEnd = false))
    }

    test("trim match ignores leading and trailing whitespace") {
        assertEquals(0, findLineSequence(listOf("    foo   ", "   bar\t"), listOf("foo", "bar"), startIndex = 0, anchorAtEnd = false))
    }

    test("target lines longer than input returns null") {
        assertEquals(null, findLineSequence(listOf("just one line"), listOf("too", "many", "lines"), startIndex = 0, anchorAtEnd = false))
    }

    test("start index skips earlier matches") {
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

    test("anchor at end only matches tail position") {
        val lines = listOf("target", "x", "other", "target", "x")
        assertEquals(3, findLineSequence(lines, listOf("target", "x"), startIndex = 0, anchorAtEnd = true))
        assertEquals(null, findLineSequence(lines, listOf("other", "target"), startIndex = 0, anchorAtEnd = true))
    }

    test("repeated prefixes do not confuse matcher") {
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

    test("unicode punctuation match normalizes common dash characters") {
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
