package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun assertSliceInvariant(slice: StdoutSlice) {
    assertEquals(
        expected = slice.nextOffset - slice.offset,
        actual = slice.head.size.toULong() + slice.omittedByteCount + slice.tail.size.toULong(),
    )
}

val stdoutBufferTest by testSuite {
    test("reads non-destructively and supports independent reader cursors") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        val writable = MutableStdoutBuffer(scope, quotaByteCount = 200)
        val readable: StdoutBuffer = writable
        try {
            writable.send("first".encodeToByteArray())
            writable.send(" second".encodeToByteArray())
            writable.flush()

            val fullA = readable.read(offset = 0uL)
            val fullB = readable.read(offset = 0uL)
            assertEquals(fullA, fullB)
            assertEquals("first second", fullA.renderedBytes().decodeToString())
            assertEquals(0uL, fullA.offset)
            assertEquals(12uL, fullA.nextOffset)
            assertEquals(12uL, fullA.totalByteCount)
            assertEquals(0uL, fullA.omittedByteCount)
            assertSliceInvariant(fullA)

            val chunk1 = readable.read(offset = 0uL, limit = 5u)
            assertEquals("first", chunk1.head.decodeToString())
            assertEquals(5uL, chunk1.nextOffset)
            assertSliceInvariant(chunk1)

            val chunk2 = readable.read(offset = chunk1.nextOffset, limit = 10u)
            assertEquals(" second", chunk2.head.decodeToString())
            assertEquals(12uL, chunk2.nextOffset)
            assertSliceInvariant(chunk2)

            val atEnd = readable.read(offset = chunk2.nextOffset)
            assertTrue(atEnd.isEmpty)
            assertEquals(12uL, atEnd.offset)
            assertEquals(12uL, atEnd.nextOffset)

            val pastEnd = readable.read(offset = 999uL, limit = 10u)
            assertTrue(pastEnd.isEmpty)
            assertEquals(12uL, pastEnd.offset)
            assertEquals(12uL, pastEnd.nextOffset)

            val zeroLimit = readable.read(offset = 4uL, limit = 0u)
            assertTrue(zeroLimit.isEmpty)
            assertEquals(4uL, zeroLimit.offset)
            assertEquals(4uL, zeroLimit.nextOffset)
            assertEquals(12uL, zeroLimit.totalByteCount)
        } finally {
            owner.cancelAndJoin()
        }
    }

    test("compares slices by content") {
        assertEquals(
            expected = StdoutSlice(
                head = "head".encodeToByteArray(),
                tail = "tail".encodeToByteArray(),
                omittedByteCount = 2uL,
                offset = 0uL,
                nextOffset = 10uL,
                totalByteCount = 10uL,
            ),
            actual = StdoutSlice(
                head = "head".encodeToByteArray(),
                tail = "tail".encodeToByteArray(),
                omittedByteCount = 2uL,
                offset = 0uL,
                nextOffset = 10uL,
                totalByteCount = 10uL,
            ),
        )
    }

    test("retains 10 percent head and 10 percent tail and handles offsets in head, omitted middle, and tail") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        // quota = 50, retention = 10% => 5 bytes head + 5 bytes tail
        val buffer = MutableStdoutBuffer(scope, quotaByteCount = 50, headTailRetentionPercent = 10)
        try {
            buffer.send("abcdefghijkl".encodeToByteArray()) // 12 bytes: head="abcde"[0..5), omitted="fg"[5..7), tail="hijkl"[7..12)
            assertTrue(buffer.close())
            buffer.flush()

            val full = buffer.read(offset = 0uL)
            assertFalse(full.isEmpty)
            assertEquals("abcde", full.head.decodeToString())
            assertEquals("hijkl", full.tail.decodeToString())
            assertEquals(2uL, full.omittedByteCount)
            assertEquals(0uL, full.offset)
            assertEquals(12uL, full.nextOffset)
            assertEquals(12uL, full.totalByteCount)
            assertEquals("abcde\n... 2 bytes omitted ...\nhijkl", full.renderedBytes().decodeToString())
            assertSliceInvariant(full)

            // Limit stops within head before omitted zone
            val headOnly = buffer.read(offset = 1uL, limit = 4u)
            assertEquals("bcde", headOnly.head.decodeToString())
            assertEquals("", headOnly.tail.decodeToString())
            assertEquals(0uL, headOnly.omittedByteCount)
            assertEquals(5uL, headOnly.nextOffset)
            assertSliceInvariant(headOnly)

            // Offset in omitted middle [5, 7) jumps to tailStart = 7 and accounts omittedByteCount = 7 - offset
            val fromMiddle = buffer.read(offset = 6uL, limit = 3u)
            assertTrue(fromMiddle.head.isEmpty())
            assertEquals(1uL, fromMiddle.omittedByteCount)
            assertEquals("hij", fromMiddle.tail.decodeToString())
            assertEquals(6uL, fromMiddle.offset)
            assertEquals(10uL, fromMiddle.nextOffset)
            assertSliceInvariant(fromMiddle)

            // Continuation inside tail [10, 12) has zero omission
            val tailContinuation = buffer.read(offset = fromMiddle.nextOffset, limit = 10u)
            assertEquals("kl", tailContinuation.head.decodeToString())
            assertTrue(tailContinuation.tail.isEmpty())
            assertEquals(0uL, tailContinuation.omittedByteCount)
            assertEquals(12uL, tailContinuation.nextOffset)
            assertSliceInvariant(tailContinuation)
        } finally {
            owner.cancelAndJoin()
        }
    }

    test("fills bounded head and ring-buffered tail across multiple chunks without splitting raw UTF-8 bytes") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        val buffer = MutableStdoutBuffer(scope, quotaByteCount = 50, headTailRetentionPercent = 10)
        try {
            for (chunk in listOf("01", "234", "567", "89", "a", "bc", "def")) {
                buffer.send(chunk.encodeToByteArray())
            }
            buffer.flush()

            // Total 16 bytes "0123456789abcdef": head="01234" (5), omitted="56789a" (6), tail="bcdef" (5)
            val slice = buffer.read()
            assertEquals("01234", slice.head.decodeToString())
            assertEquals("bcdef", slice.tail.decodeToString())
            assertEquals(6uL, slice.omittedByteCount)
            assertEquals(16uL, slice.totalByteCount)
            assertSliceInvariant(slice)
        } finally {
            owner.cancelAndJoin()
        }

        val unicodeOwner = SupervisorJob()
        val unicodeScope = CoroutineScope(unicodeOwner)
        val unicodeBuffer = MutableStdoutBuffer(unicodeScope, quotaByteCount = 200)
        try {
            val rawUtf8 = "你好世界🚀".encodeToByteArray()
            unicodeBuffer.send(rawUtf8)
            unicodeBuffer.flush()

            val part1 = unicodeBuffer.read(offset = 0uL, limit = 2u) // cuts mid 3-byte UTF-8 sequence as raw bytes
            val part2 = unicodeBuffer.read(offset = part1.nextOffset, limit = 100u)
            assertContentEquals(rawUtf8, part1.head + part2.head)
        } finally {
            unicodeOwner.cancelAndJoin()
        }
    }

    test("nonterminal cancelled owner throws CancellationException while terminal buffer remains readable") {
        val cancelledOwner = SupervisorJob()
        val cancelledBuffer = MutableStdoutBuffer(CoroutineScope(cancelledOwner))
        cancelledOwner.cancel()
        assertFailsWith<CancellationException> {
            cancelledBuffer.read()
        }

        val terminalOwner = SupervisorJob()
        val terminalBuffer = MutableStdoutBuffer(CoroutineScope(terminalOwner))
        try {
            terminalBuffer.send("done".encodeToByteArray())
            terminalBuffer.flush()
            terminalBuffer.signalTerminal()
            terminalBuffer.finish()
        } finally {
            terminalOwner.cancel()
        }
        val finalSlice = terminalBuffer.read()
        assertEquals("done", finalSlice.head.decodeToString())
    }
}
