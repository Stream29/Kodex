package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

internal actual class PlatformProcessClient actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    ProcessClient {

    actual override suspend fun start(command: ProcessCommand): ProcessSession =
        this@PlatformProcessClient.acquireProcessSession(Dispatchers.IO) {
            this@PlatformProcessClient.requireOpen()
            val process = try {
                ProcessBuilder(listOf(command.executable) + command.arguments)
                    .redirectErrorStream(false)
                    .directory(File(command.workingDirectory.toString()))
                    .apply { environment().putAll(command.environment) }
                    .start()
            } catch (failure: IOException) {
                throw ProcessException("Failed to start process with ${command.executable}.", failure)
            }
            JvmProcessSession(process, this@PlatformProcessClient)
        }

    actual override fun close() {
        cancel()
    }
}

@OptIn(ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
private class JvmProcessSession(
    private val process: Process,
    ownerScope: CoroutineScope,
) : ProcessSession {
    private val processStdin = BlockingCoroutineRawSink(process.outputStream.asSink(), Dispatchers.IO)
    private val processStdout = BlockingCoroutineRawSource(process.inputStream.asSource(), Dispatchers.IO)
    private val processStderr = BlockingCoroutineRawSource(process.errorStream.asSource(), Dispatchers.IO)
    override val stdin: CoroutineRawSink = processStdin
    override val stdout: CoroutineRawSource = processStdout
    override val stderr: CoroutineRawSource = processStderr
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val releaseResult = CompletableDeferred<Result<Unit>>()
    private var cancellationGuard: Job? = null
    private val exitObserver: Job

    init {
        // Atomic start installs finally even if the owner cancels before dispatch.
        // Nullable registration permits cleanup to run before assignment.
        cancellationGuard = ownerScope.launchProcessCancellationGuard(Dispatchers.IO) { runCatching { close() } }
        exitObserver = ownerScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                exitCode.complete(process.waitFor())
            } catch (failure: Throwable) {
                exitCode.completeExceptionally(failure)
            }
        }
        exitObserver.invokeOnCompletion { failure -> if (failure != null) runCatching { close() } }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        cancellationGuard?.cancel()
        var cleanup: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                val first = cleanup
                if (first == null) cleanup = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        var alive = false
        attempt { alive = process.isAlive }
        if (alive) {
            var descendants: List<ProcessHandle> = emptyList()
            attempt { descendants = process.descendants().use { it.toList() } }
            descendants.asReversed().forEach { child -> attempt { child.destroy() } }
            attempt { process.destroy() }
            var exited = false
            attempt { exited = process.waitFor(ProcessExitGraceMillis, TimeUnit.MILLISECONDS) }
            if (!exited) {
                descendants.asReversed().forEach { child -> attempt { child.destroyForcibly() } }
                attempt { process.destroyForcibly() }
            }
        }
        attempt { processStdin.closeImmediately() }
        attempt { exitCode.complete(process.waitFor()) }
        attempt { processStdout.closeImmediately() }
        attempt { processStderr.closeImmediately() }
        val failure = cleanup
        releaseResult.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        failure?.let { throw it }
    }

    override suspend fun closeAndJoin(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        var primary: Throwable? = null
        try {
            withTimeout(7.seconds) {
                // A racing guard may have won close; its saved result is the barrier.
                runCatching { close() }
                primary = releaseResult.await().exceptionOrNull()
                cancellationGuard?.join()
                exitObserver.join()
                primary?.let { throw it }
            }
        } catch (waiting: Throwable) {
            val first = primary
            if (first != null && first !== waiting) { first.addSuppressed(waiting); throw first }
            throw waiting
        }
    }
}

private const val ProcessExitGraceMillis: Long = 500L
