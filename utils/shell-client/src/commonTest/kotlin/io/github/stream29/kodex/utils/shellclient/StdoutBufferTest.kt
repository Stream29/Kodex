package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val stdoutBufferTest by testSuite {
    test("exposes only destructive reads through StdoutBuffer") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        val writable = MutableStdoutBuffer(scope, maximumRetainedByteCount = 32)
        val readable: StdoutBuffer = writable
        try {
            writable.send("first".encodeToByteArray())
            writable.send(" second".encodeToByteArray())
            writable.flush()

            val first: StdoutBufferSnapshot = readable.drain()
            assertEquals("first second", first.renderedBytes().decodeToString())
            assertEquals(12L, first.originalByteCount)
            assertEquals(0L, first.omittedByteCount)

            assertTrue(readable.drain().isEmpty)
        } finally {
            owner.cancelAndJoin()
        }
    }

    test("compares retained bytes by content") {
        assertEquals(
            expected = StdoutBufferSnapshot(
                head = "head".encodeToByteArray(),
                tail = "tail".encodeToByteArray(),
                omittedByteCount = 2L,
            ),
            actual = StdoutBufferSnapshot(
                head = "head".encodeToByteArray(),
                tail = "tail".encodeToByteArray(),
                omittedByteCount = 2L,
            ),
        )
    }

    test("retains a bounded output head and tail") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        val buffer = MutableStdoutBuffer(scope, maximumRetainedByteCount = 10)
        try {
            buffer.send("abcdefghijkl".encodeToByteArray())
            assertTrue(buffer.close())
            buffer.flush()

            val output: StdoutBufferSnapshot = buffer.drain()
            assertEquals("abcde", output.head.decodeToString())
            assertEquals("hijkl", output.tail.decodeToString())
            assertEquals("abcde\n... 2 bytes omitted ...\nhijkl", output.renderedBytes().decodeToString())
            assertEquals(12L, output.originalByteCount)
            assertEquals(2L, output.omittedByteCount)
        } finally {
            owner.cancelAndJoin()
        }
    }

    test("fills the bounded head and tail across input chunks") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        val buffer = MutableStdoutBuffer(scope, maximumRetainedByteCount = 10)
        try {
            for (chunk in listOf("01", "234", "567", "89", "a")) {
                buffer.send(chunk.encodeToByteArray())
            }
            buffer.flush()

            val output: StdoutBufferSnapshot = buffer.drain()
            assertEquals("01234", output.head.decodeToString())
            assertEquals("6789a", output.tail.decodeToString())
            assertEquals(1L, output.omittedByteCount)
            assertEquals(11L, output.originalByteCount)
        } finally {
            owner.cancelAndJoin()
        }
    }

    test("read waits for output and consumes it") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val writable = MutableStdoutBuffer(scope)
        val readable: StdoutBuffer = writable
        try {
            coroutineScope {
                val read = async(start = CoroutineStart.UNDISPATCHED) {
                    readable.read(30.seconds)
                }
                writable.send("available".encodeToByteArray())

                assertEquals("available", read.await().renderedBytes().decodeToString())
            }
        } finally {
            owner.cancelAndJoin()
        }
    }

    test("terminal buffer returns an empty read immediately") {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner)
        val buffer = MutableStdoutBuffer(scope)
        try {
            buffer.signalTerminal()

            val output = withTimeout(1.seconds) {
                buffer.read(30.seconds)
            }
            assertTrue(output.isEmpty)
        } finally {
            owner.cancelAndJoin()
        }
    }
}
