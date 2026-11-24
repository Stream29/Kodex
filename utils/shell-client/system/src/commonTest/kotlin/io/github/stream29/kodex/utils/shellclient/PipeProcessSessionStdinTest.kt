package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.ProcessSession as RawProcessSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val pipeProcessSessionStdinTest by testSuite {
    test("root exit and both EOFs cancel a blocked write before joining native cleanup") {
        withPipeFixture {
            val acknowledged = send("first")
            dispatcher.runQueued()
            acknowledged.completedValue().getOrThrow()

            val writing = send("blocked")
            dispatcher.runQueued()
            assertTrue(raw.stdin.blocked.isCompleted)
            val queued = send("queued")
            assertFalse(writing.isCompleted)
            assertFalse(queued.isCompleted)

            raw.exitCode.complete(37)
            raw.stdout.end.complete(null)
            dispatcher.runQueued()
            assertFalse(session.exitCode.isCompleted)
            assertFalse(raw.stdin.cancelled)
            assertFalse(writing.isCompleted)

            raw.stderr.end.complete(null)
            dispatcher.runQueued()
            assertEquals(37, session.exitCode.completedValue())
            assertEquals("stdout tail", session.standardOutput.read().renderedBytes().decodeToString())
            assertEquals("stderr tail", session.standardError.read().renderedBytes().decodeToString())
            assertTrue(raw.stdin.cancelled)
            assertIs<ClosedSendChannelException>(writing.completedValue().exceptionOrNull())
            val queuedFailure = queued.completedValue().exceptionOrNull()
            assertTrue(queuedFailure is CancellationException || queuedFailure is ClosedSendChannelException)
            assertIs<ClosedSendChannelException>(send("late").completedValue().exceptionOrNull())
            acknowledged.completedValue().getOrThrow()
            assertEquals(listOf("first", "blocked"), raw.stdin.writes)
            assertFinishedOnceAfterRawClose()
        }
    }

    test("a rendezvous handed off before exit still settles when the receiver resumes after both EOFs") {
        withPipeFixture {
            val sent = send("handoff")
            // send has transferred the value. Leave receiveCatching suspended before claim().
            val resumeReceiver = dispatcher.takeOnlyQueued()
            assertFalse(sent.isCompleted)
            try {
                rootAndEof()
                dispatcher.runQueued()
                assertEquals(37, session.exitCode.completedValue())
                assertFalse(sent.isCompleted)
                assertTrue(raw.stdin.writes.isEmpty())
                assertEquals(0, raw.closeCalls)
            } finally {
                resumeReceiver.run()
            }
            dispatcher.runQueued()
            assertIs<ClosedSendChannelException>(sent.completedValue().exceptionOrNull())
            assertTrue(raw.stdin.writes.isEmpty())
            assertFinishedOnceAfterRawClose()
        }
    }

    test("an output error after raw exit does not count as genuine EOF for stdin shutdown") {
        withPipeFixture {
            val sent = send("blocked")
            dispatcher.runQueued()
            assertTrue(raw.stdin.blocked.isCompleted)

            raw.exitCode.complete(37)
            raw.stdout.end.complete(null)
            raw.stderr.end.complete(IOException("Output stream closed without an EOF."))
            dispatcher.runQueued()
            assertEquals(37, session.exitCode.completedValue())
            assertFalse(raw.stdin.cancelled)
            assertFalse(sent.isCompleted)
            assertEquals(0, raw.closeCalls)

            raw.stdin.allowWrite.complete(Unit)
            dispatcher.runQueued()
            sent.completedValue().getOrThrow()
            assertFinishedOnceAfterRawClose()
        }
    }

    test("a genuine read failure before raw exit retains the original failure") {
        withPipeFixture {
            val failure = IOException("fixture read failed")
            raw.stdout.end.complete(failure)
            dispatcher.runQueued()
            assertTrue(session.exitCode.isCompleted)
            val observed = assertFailsWith<ProcessException> { session.exitCode.await() }
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            assertEquals(1, raw.closeCalls)
            raw.allowClose.complete(Unit)
            dispatcher.runQueued()
            assertTrue(sessionJob.isCompleted)
        }
    }
}

private suspend fun withPipeFixture(block: suspend PipeStdinFixture.() -> Unit) {
    val fixture = PipeStdinFixture()
    try {
        fixture.dispatcher.runQueued()
        fixture.block()
    } finally {
        fixture.raw.allowClose.complete(Unit)
        fixture.owner.cancel()
        fixture.dispatcher.runQueued()
    }
}

private class PipeStdinFixture {
    val dispatcher = PipeTestDispatcher()
    val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)
    val raw = FakeRawPipe()
    val session = PipeProcessSession(raw, scope)
    val sessionJob = requireNotNull(session.scope.coroutineContext[Job])
    private val closeEvents = mutableListOf<Throwable?>()

    init {
        session.stdin.invokeOnClose { closeEvents.add(it) }
    }

    fun send(text: String): Deferred<Result<Unit>> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { session.stdin.send(text) }
    }

    fun rootAndEof() {
        raw.exitCode.complete(37)
        raw.stdout.end.complete(null)
        raw.stderr.end.complete(null)
    }

    fun assertFinishedOnceAfterRawClose() {
        assertEquals(listOf<Throwable?>(null), closeEvents)
        assertEquals(1, raw.closeCalls)
        assertFalse(sessionJob.isCompleted)
        session.close()
        raw.allowClose.complete(Unit)
        dispatcher.runQueued()
        assertTrue(sessionJob.isCompleted)
        assertTrue(owner.isActive)
        assertEquals(1, raw.closeCalls)
    }
}

private class FakeRawPipe : RawProcessSession {
    override val exitCode = CompletableDeferred<Int>()
    override val stdin = GatedPipeInput()
    override val stdout = GatedPipeOutput("stdout tail")
    override val stderr = GatedPipeOutput("stderr tail")
    val allowClose = CompletableDeferred<Unit>()
    var closeCalls = 0
        private set

    override fun close() = error("Expected the owning shell session to close and join")

    override suspend fun closeAndJoin() {
        closeCalls++
        allowClose.await()
    }
}

private class GatedPipeInput : CoroutineRawSink {
    val blocked = CompletableDeferred<Unit>()
    val allowWrite = CompletableDeferred<Unit>()
    val writes = mutableListOf<String>()
    var cancelled = false
        private set

    override suspend fun write(source: Buffer, byteCount: Long) {
        val text = source.readByteArray(byteCount.toInt()).decodeToString()
        writes.add(text)
        if (text == "blocked") {
            blocked.complete(Unit)
            try {
                allowWrite.await()
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
    }

    override suspend fun flush() = Unit
    override suspend fun close() = Unit
}

private class GatedPipeOutput(text: String) : CoroutineRawSource {
    val end = CompletableDeferred<Throwable?>()
    private val payload = Buffer().apply { write(text.encodeToByteArray()) }

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val failure = end.await()
        if (payload.size > 0L) return payload.readAtMostTo(sink, byteCount)
        failure?.let { throw it }
        return -1L
    }

    override suspend fun close() = Unit
}

private class PipeTestDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun takeOnlyQueued(): Runnable {
        assertEquals(1, queued.size)
        return queued.removeFirst()
    }

    fun runQueued() {
        repeat(1_000) { (queued.removeFirstOrNull() ?: return).run() }
        error("Fixture did not reach a suspension point")
    }
}

private suspend fun <T> Deferred<T>.completedValue(): T {
    assertTrue(isCompleted, "Expected the fixture to settle without another event")
    return await()
}
