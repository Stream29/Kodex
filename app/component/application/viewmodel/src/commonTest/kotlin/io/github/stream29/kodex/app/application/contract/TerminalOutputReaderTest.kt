package io.github.stream29.kodex.app.application.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
val terminalOutputReaderTest by testSuite {
    test("no eager reads, idle polls every 200 ms and an in-flight pre-exit snapshot cannot finish the reader") {
        runTest {
            val calls = mutableListOf<Pair<Long, ULong>>()
            val release = CompletableDeferred<Unit>()
            val session = TestTerminal { offset ->
                calls += testScheduler.currentTime to offset
                when (calls.size) {
                    1 -> output(offset, "ready\n")
                    2 -> { release.await(); output(offset) }
                    3 -> output(offset, "尾\n")
                    else -> error("Poll after exit")
                }
            }
            val registry = TestRegistry(session)
            val reader = TerminalOutputReader(session, registry)
            assertTrue(calls.isEmpty())
            val observing = launch { reader.observe() }
            runCurrent()
            assertEquals("ready\n", reader.state.value.plainText())
            val visible = reader.state.value
            advanceTimeBy(199)
            runCurrent()
            assertEquals(listOf(0L to 0uL), calls)
            assertSame(visible, reader.state.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, calls.size)
            session.exitCode.value = 7
            release.complete(Unit)
            runCurrent()
            observing.join()
            assertEquals(listOf(0L to 0uL, 200L to 6uL, 200L to 6uL), calls)
            assertEquals(TerminalOutputPhase.Complete(7), reader.state.value.phase)
            assertEquals("ready\n尾\n", reader.state.value.plainText())
            registry.activeSessions.value = emptyMap()
            advanceTimeBy(2_000)
            assertEquals(3, calls.size)
            assertEquals(TerminalOutputPhase.Complete(7), reader.state.value.phase)
        }
    }

    test("exit interrupts an empty waiting interval and a zero-byte final read is sufficient") {
        runTest {
            val reads = mutableListOf<Long>()
            val shell = TestTerminal { offset -> reads += testScheduler.currentTime; output(offset) }
            val reader = TerminalOutputReader(shell, TestRegistry(shell))
            val job = launch { reader.observe() }
            runCurrent()
            advanceTimeBy(40)
            shell.exitCode.value = 0
            runCurrent()
            job.join()
            assertEquals(listOf(0L, 40L), reads)
            assertTrue(reader.state.value.items.isEmpty())
            assertEquals(TerminalOutputPhase.Complete(0), reader.state.value.phase)
        }
    }

    test("independent cursors drain backlog, tolerate loss and never splice UTF-8 or terminal controls through a gap") {
        runTest {
            val prefix = "中😀\r\nabc\t\u001b[31mpaint\u001b[0m\u001b]52;c;PRIVATE\u0007\r".encodeToByteArray()
            val first = prefix.copyOfRange(0, 5)
            val second = prefix.copyOfRange(5, prefix.size) + byteArrayOf(0xf0.toByte(), 0x9f.toByte())
            val tail = byteArrayOf(0x98.toByte(), 0x80.toByte()) +
                "last\u009b31mx\u0090hidden\u009c!\u001b]unfinished".encodeToByteArray()
            val skipped = 11uL
            val end = (first.size + second.size + tail.size).toULong() + skipped
            val reads = mutableListOf<ULong>()
            val shell = TestTerminal { offset ->
                reads += offset
                if (offset == 0uL) StdoutSlice(first, byteArrayOf(), 0uL, offset, first.size.toULong(), end)
                else StdoutSlice(second, tail, skipped, offset, end, end)
            }.apply { exitCode.value = 4 }
            val registry = TestRegistry(shell)
            val left = TerminalOutputReader(shell, registry)
            val right = TerminalOutputReader(shell, registry)
            left.observe()
            right.observe()
            assertEquals(listOf(0uL, 5uL, 0uL, 5uL), reads)
            assertEquals("中😀\nabc    paint\n�", left.state.value.items.takeWhile { it is TerminalOutputItem.Text }.map {
                it as TerminalOutputItem.Text
                it.text + if (it.endsLine) "\n" else ""
            }.joinToString(""))
            assertEquals("lastx!", left.state.value.plainText().substringAfter("���"))
            assertEquals(11uL, left.state.value.items.filterIsInstance<TerminalOutputItem.BackendOmission>().single().bytes)
            assertEquals(left.state.value.items, right.state.value.items)
            assertEquals(TerminalOutputPhase.Complete(4), left.state.value.phase)
        }
    }

    test("the open line keeps its key and prior snapshots through appends; dense lines and wide log fragments stay bounded") {
        val window = TerminalOutputWindow()
        window.append('a'.code)
        val first = assertNotNull(window.takeSnapshot())
        window.append('b'.code)
        val next = assertNotNull(window.takeSnapshot())
        assertEquals(first.single().id, next.single().id)
        assertEquals("a", assertIs<TerminalOutputItem.Text>(first.single()).text)
        assertEquals("ab", assertIs<TerminalOutputItem.Text>(next.single()).text)
        assertNull(window.takeSnapshot())
        repeat(70_000) { window.append('\n'.code) }
        val dense = assertNotNull(window.takeSnapshot())
        assertEquals(4_096, dense.size)
        assertEquals(70_000L - 4_095, assertIs<TerminalOutputItem.WindowEviction>(dense.first()).items)
        repeat(400_000) { window.append(0x1f642) }
        val long = assertNotNull(window.takeSnapshot())
        val pieces = long.filterIsInstance<TerminalOutputItem.Text>()
        assertTrue(pieces.sumOf { it.text.length * 2 } <= 1_048_576)
        assertTrue(pieces.all { it.text.length <= 4_096 &&
            (it.text.isEmpty() || (it.text.first() in '\ud800'..'\udbff' && it.text.last() in '\udc00'..'\udfff')) })
        assertEquals(long.size, long.map { it.id }.distinct().size)
        assertEquals("a", (first.single() as TerminalOutputItem.Text).text)
    }

    test("registry replacement cancels an in-flight read and preserves only the opened inspector snapshot") {
        runTest {
            val readReleased = CompletableDeferred<Unit>()
            var calls = 0
            val original = TestTerminal { offset ->
                if (++calls == 1) output(offset, "original") else {
                    try { awaitCancellation() } finally { readReleased.complete(Unit) }
                }
            }
            val replacement = TestTerminal { offset -> output(offset, "different") }.apply { exitCode.value = 2 }
            val registry = TestRegistry(original)
            val reader = TerminalOutputReader(original, registry)
            val job = launch { reader.observe() }
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(2, calls)
            registry.activeSessions.value = mapOf(original.sessionId to replacement)
            runCurrent()
            job.join()
            readReleased.await()
            assertEquals(TerminalOutputPhase.Removed, reader.state.value.phase)
            assertEquals("original", reader.state.value.plainText())
            val other = TerminalOutputReader(replacement, registry)
            other.observe()
            assertEquals("different", other.state.value.plainText())
            assertEquals(0, original.closeCalls + replacement.closeCalls)
        }
    }

    test("caller cancellation is not a read failure, and typed removal and session inactivity remain distinct") {
        runTest {
            val shell = TestTerminal { awaitCancellation() }
            val reader = TerminalOutputReader(shell, TestRegistry(shell))
            val job = launch { reader.observe() }
            runCurrent()
            job.cancelAndJoin()
            assertEquals(TerminalOutputPhase.Reading, reader.state.value.phase)
            for ((failure, expected) in listOf(
                ShellSessionNotRegistered() to TerminalOutputPhase.Removed,
                SessionNotActive() to TerminalOutputPhase.SessionInactive,
                IllegalStateException("private reason") to TerminalOutputPhase.ReadFailed,
            )) {
                val broken = TestTerminal { throw failure }
                val stopped = TerminalOutputReader(broken, TestRegistry(broken))
                stopped.observe()
                assertEquals(expected, stopped.state.value.phase)
                assertEquals(1, broken.reads)
                assertEquals(0, broken.closeCalls)
            }
            val backwards = TestTerminal { offset -> output(offset + 1uL, "unsafe") }
            val invalid = TerminalOutputReader(backwards, TestRegistry(backwards))
            invalid.observe()
            assertEquals(TerminalOutputPhase.ReadFailed, invalid.state.value.phase)
            assertTrue(invalid.state.value.items.isEmpty())
        }
    }
}

private class TestTerminal(private val read: suspend (ULong) -> StdoutSlice) : AgentShellSession {
    override val sessionId = 1
    override val arguments = ExecCommandArguments("fixture", name = "Inspect fixture output", yieldTimeMillis = 10_000)
    override val exitCode = MutableStateFlow<Int?>(null)
    override val completed = MutableStateFlow(false)
    var reads = 0
    var closeCalls = 0
    override suspend fun readOutput(offset: ULong, limit: UInt): StdoutSlice {
        assertEquals(65_536u, limit)
        reads++
        return read(offset)
    }
    override fun close() { closeCalls++ }
}

private class TestRegistry(vararg shells: AgentShellSession) : AgentShellSessionRegistry {
    override val activeSessions = MutableStateFlow(shells.associateBy { it.sessionId })
}

private fun output(offset: ULong, text: String = ""): StdoutSlice {
    val encoded = text.encodeToByteArray()
    val end = offset + encoded.size.toULong()
    return StdoutSlice(encoded, byteArrayOf(), 0uL, offset, end, end)
}

private fun TerminalOutputState.plainText(): String = buildString {
    items.filterIsInstance<TerminalOutputItem.Text>().forEach {
        append(it.text)
        if (it.endsLine) append('\n')
    }
}
