package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val pipeProcessLifetimeTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("close dispatches blocking raw termination and parent join includes cleanup") {
        val owner = Job()
        val raw = GatedRawProcess()
        val session = PipeProcessSession(raw, CoroutineScope(owner + Dispatchers.Default))
        try {
            coroutineScope {
                val returned = CompletableDeferred<Unit>()
                // Deliberately execute close inline on the caller. The raw close
                // gate must not prevent the next caller statement from running.
                val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.close()
                    session.close()
                    returned.complete(Unit)
                }
                withTimeout(3.seconds) {
                    returned.await()
                    raw.closeEntered.await()
                }
                assertTrue(session.scope.coroutineContext[Job] in owner.children.toList())
                val join = async(start = CoroutineStart.UNDISPATCHED) { owner.cancelAndJoin() }
                assertFalse(join.isCompleted, "Owner finished while raw close still held the resource")
                raw.allowClose.countDown()
                withTimeout(3.seconds) { join.await(); caller.join() }
                assertTrue(raw.released.get())
                assertTrue(owner.children.none())
            }
        } finally {
            raw.allowClose.countDown()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("known platform status does not replace primary input and suppressed cleanup failure") {
        val primary = IOException("fixture input write")
        val cleanup = IOException("fixture raw close")
        val owner = Job()
        val raw = GatedRawProcess(primary, cleanup).also { it.allowClose.countDown() }
        raw.exitCode.complete(0)
        val session = PipeProcessSession(raw, CoroutineScope(owner + Dispatchers.Default))
        try {
            val inputFailure = assertFailsWith<IOException> { session.stdin.send("input") }
            assertTrue(generateSequence<Throwable>(inputFailure) { it.cause }.any { it === primary })
            val exitFailure = assertFailsWith<IOException> { session.exitCode.await() }
            assertTrue(generateSequence<Throwable>(exitFailure) { it.cause }.any { it === primary })
            withTimeout(3.seconds) { session.scope.coroutineContext[Job]!!.join() }
            assertTrue(primary.suppressedExceptions.any { it === cleanup })
            assertEquals(0, raw.exitCode.await())
            assertTrue(raw.released.get())
            assertTrue(owner.isActive)
        } finally {
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("cancelled shell return joins original resource child and suppresses cleanup failure") {
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val cleanup = IOException("fixture unclaimed shell cleanup")
        val raw = GatedRawProcess(cleanupFailure = cleanup)
        try {
            coroutineScope {
                val returns = ShellReturnGateDispatcher()
                val observed = CompletableDeferred<Throwable>()
                val caller = launch(returns) {
                    try {
                        scope.acquireShellSession(Dispatchers.Default) { PipeProcessSession(raw, scope) }
                        error("Cancelled shell handoff returned its resource")
                    } catch (failure: CancellationException) {
                        observed.complete(failure)
                        throw failure
                    }
                }
                withTimeout(3.seconds) { returns.pending.receive() }.run()
                val handoff = withTimeout(3.seconds) { returns.pending.receive() }
                val primary = CancellationException("fixture shell handoff cancellation")
                caller.cancel(primary)
                val pump = launch(start = CoroutineStart.UNDISPATCHED) {
                    handoff.run()
                    for (continuation in returns.pending) continuation.run()
                }
                try {
                    withTimeout(3.seconds) { raw.closeEntered.await() }
                    assertFalse(caller.isCompleted)
                    raw.allowClose.countDown()
                    withTimeout(3.seconds) { caller.join() }
                    val failure = observed.await()
                    assertTrue(generateSequence(failure) { it.cause }.any { it === primary })
                    assertTrue(generateSequence(failure) { it.cause }
                        .flatMap { it.suppressedExceptions.asSequence() }.any { it === cleanup })
                    assertTrue(raw.released.get())
                    assertTrue(owner.isActive)
                    assertTrue(owner.children.none())
                } finally {
                    raw.allowClose.countDown()
                    withContext(NonCancellable) { withTimeout(5.seconds) { caller.join() } }
                    returns.pending.close()
                    pump.join()
                }
            }
        } finally {
            raw.allowClose.countDown()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("real isolated JVM child forces termination without cancelling its parent") {
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val client = scope.ProcessClient()
        var session: ProcessSession? = null
        try {
            val executable = File(
                System.getProperty("java.home"),
                "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            ).absolutePath
            val raw = client.start(ProcessCommand(
                executable,
                listOf("-cp", fixtureClassPath(), SlowTerminationFixture::class.java.name),
            ))
            val started = PipeProcessSession(raw, scope)
            session = started
            withTimeout(5.seconds) {
                val output = StringBuilder()
                while ("fixture-ready" !in output) {
                    output.append(started.stdout.read(100.milliseconds).renderedBytes().decodeToString())
                }
            }
            started.close()
            started.close()
            assertTrue(withTimeout(5.seconds) { started.exitCode.await() } != 0)
            withTimeout(5.seconds) { started.scope.coroutineContext[Job]!!.join() }
            assertTrue(owner.isActive)
            assertTrue(raw.exitCode.isCompleted)
        } finally {
            session?.close()
            client.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }
}

private fun fixtureClassPath(): String =
    listOf(SlowTerminationFixture::class.java, Unit::class.java)
        .map { File(requireNotNull(it.protectionDomain.codeSource).location.toURI()).absolutePath }
        .distinct()
        .joinToString(File.pathSeparator)

private class ShellReturnGateDispatcher : CoroutineDispatcher() {
    val pending = Channel<Runnable>(Channel.UNLIMITED)
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(pending.trySend(block).isSuccess)
    }
}

private class GatedRawProcess(
    private val inputFailure: Throwable? = null,
    private val cleanupFailure: Throwable? = null,
) : io.github.stream29.kodex.utils.processclient.ProcessSession {
    val closeEntered = CompletableDeferred<Unit>()
    val allowClose = CountDownLatch(1)
    val released = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val releaseResult = CompletableDeferred<Result<Unit>>()
    override val exitCode = CompletableDeferred<Int>()
    override val stdout = suspendedSource()
    override val stderr = suspendedSource()
    override val stdin = object : CoroutineRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            inputFailure?.let { throw it }
            source.skip(byteCount)
        }
        override suspend fun flush() = Unit
        override suspend fun close() = Unit
    }
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        closeEntered.complete(Unit)
        val result = runCatching<Unit> {
            check(allowClose.await(10, TimeUnit.SECONDS)) { "Test failed to release raw close gate" }
            released.set(true)
            exitCode.complete(143)
            cleanupFailure?.let { throw it }
        }
        releaseResult.complete(result)
        result.getOrThrow()
    }
    override suspend fun closeAndJoin() {
        runCatching { close() }
        releaseResult.await().getOrThrow()
    }
    private fun suspendedSource() = object : CoroutineRawSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = awaitCancellation()
        override suspend fun close() = Unit
    }
}

/** Only launched as a test-owned subprocess; never installs hooks in the runner. */
object SlowTerminationFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(30_000) })
        System.out.println("fixture-ready")
        System.out.flush()
        CountDownLatch(1).await()
    }
}
