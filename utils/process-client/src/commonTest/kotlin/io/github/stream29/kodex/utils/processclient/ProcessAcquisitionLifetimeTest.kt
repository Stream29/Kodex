package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

val processAcquisitionLifetimeTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("owner cancellation during acquisition cannot finish before owned rollback") {
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val acquired = AcquisitionRawProcess().also { it.exitCode.complete(143) }
        val primary = CancellationException("fixture owner cancellation during acquisition")
        try {
            assertSame(primary, assertFailsWith<CancellationException> {
                scope.acquireProcessSession(Dispatchers.Default) {
                    owner.cancel(primary)
                    assertFalse(owner.isCompleted, "No original-owner acquisition child held rollback")
                    acquired
                }
            })
            withTimeout(3.seconds) { owner.join() }
            assertTrue(acquired.closed)
            assertTrue(owner.children.none())
        } finally {
            owner.cancel()
            acquired.exitCode.complete(143)
            owner.join()
        }
    }

    test("cancelled dispatcher handoff waits for unclaimed process exit") {
        coroutineScope {
            val returns = AcquisitionReturnDispatcher()
            val acquired = AcquisitionRawProcess()
            val observed = CompletableDeferred<Throwable>()
            val caller = launch(returns) {
                try {
                    CoroutineScope(currentCoroutineContext()).acquireProcessSession(Dispatchers.Default) { acquired }
                    error("Cancelled handoff returned its resource")
                } catch (failure: CancellationException) {
                    observed.complete(failure)
                    throw failure
                }
            }
            withTimeout(3.seconds) { returns.pending.receive() }.run()
            val handoff = withTimeout(3.seconds) { returns.pending.receive() }
            val primary = CancellationException("fixture handoff cancellation")
            caller.cancel(primary)
            val pump = launch(start = CoroutineStart.UNDISPATCHED) {
                handoff.run()
                for (continuation in returns.pending) continuation.run()
            }
            try {
                withTimeout(3.seconds) { acquired.closeEntered.await() }
                assertFalse(caller.isCompleted, "Acquisition returned before its unclaimed child exit")
                acquired.exitCode.complete(143)
                withTimeout(3.seconds) { caller.join() }
                assertTrue(generateSequence(observed.await()) { it.cause }.any { it === primary })
                assertTrue(acquired.closed)
            } finally {
                acquired.exitCode.complete(143)
                caller.cancel()
                withContext(NonCancellable) { withTimeout(8.seconds) { caller.join() } }
                returns.pending.close()
                pump.join()
            }
        }
    }

    test("cancelled handoff keeps primary identity and suppresses raw cleanup failure") {
        coroutineScope {
            val returns = AcquisitionReturnDispatcher()
            val cleanup = IOException("fixture acquisition cleanup")
            val acquired = AcquisitionRawProcess(cleanup)
            val observed = CompletableDeferred<Throwable>()
            val caller = launch(returns) {
                try {
                    CoroutineScope(currentCoroutineContext()).acquireProcessSession(Dispatchers.Default) { acquired }
                    error("Cancelled handoff returned its resource")
                } catch (failure: CancellationException) {
                    observed.complete(failure)
                    throw failure
                }
            }
            withTimeout(3.seconds) { returns.pending.receive() }.run()
            val handoff = withTimeout(3.seconds) { returns.pending.receive() }
            val primary = CancellationException("fixture handoff cancellation")
            caller.cancel(primary)
            val pump = launch(start = CoroutineStart.UNDISPATCHED) {
                handoff.run()
                for (continuation in returns.pending) continuation.run()
            }
            try {
                withTimeout(3.seconds) { caller.join() }
                val failure = observed.await()
                assertTrue(generateSequence(failure) { it.cause }.any { it === primary })
                assertTrue(generateSequence(failure) { it.cause }
                    .flatMap { it.suppressedExceptions.asSequence() }.any { it === cleanup })
                assertTrue(acquired.closed)
            } finally {
                acquired.exitCode.complete(143)
                caller.cancel()
                withContext(NonCancellable) { withTimeout(8.seconds) { caller.join() } }
                returns.pending.close()
                pump.join()
            }
        }
    }
}

private class AcquisitionReturnDispatcher : CoroutineDispatcher() {
    val pending = Channel<Runnable>(Channel.UNLIMITED)
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(pending.trySend(block).isSuccess)
    }
}

private class AcquisitionRawProcess(private val cleanupFailure: Throwable? = null) : ProcessSession {
    var closed = false
        private set
    val closeEntered = CompletableDeferred<Unit>()
    override val exitCode = CompletableDeferred<Int>()
    override val stdin = object : CoroutineRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) = Unit
        override suspend fun flush() = Unit
        override suspend fun close() = Unit
    }
    override val stdout = object : CoroutineRawSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1
        override suspend fun close() = Unit
    }
    override val stderr: CoroutineRawSource = stdout
    override fun close() {
        closed = true
        closeEntered.complete(Unit)
        cleanupFailure?.let { exitCode.complete(143); throw it }
    }
    override suspend fun closeAndJoin() {
        val failure = runCatching { close() }.exceptionOrNull()
        exitCode.await()
        failure?.let { throw it }
    }
}
