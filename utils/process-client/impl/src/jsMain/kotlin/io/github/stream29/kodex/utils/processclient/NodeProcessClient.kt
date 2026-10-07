@file:Suppress("UnsafeCastFromDynamic")

package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import js.array.toJsArray
import js.objects.Object
import js.objects.unsafeJso
import js.typedarrays.toByteArray
import js.typedarrays.toUint8Array
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import node.buffer.Buffer as NodeBuffer
import node.childProcess.ChildProcessWithoutNullStreams
import node.childProcess.SpawnOptionsWithoutStdio
import node.childProcess.spawn
import node.events.EventListener
import node.events.EventType
import node.os.platform
import node.process.Process
import node.process.ProcessEnv
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private const val PosixSigkill: Double = 9.0
private const val NodeProcessIoChunkSize: Int = 64 * 1024

private val isWindowsNode: Boolean
    get() = platform().toString() == "win32"

/**
 * The generated `node:process` wrapper targets its unavailable CommonJS
 * `default` export. The Node runtime global retains the wrapper's [Process]
 * shape and exposes the process-group `kill` operation.
 */
@JsName("process")
private external val currentNodeProcess: Process

internal actual class PlatformProcessClient actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    ProcessClient {

    actual override suspend fun start(command: ProcessCommand): ProcessSession {
        this@PlatformProcessClient.requireOpen()
        if (command.executable.isBlank()) {
            throw ProcessException("Process executable must not be blank.")
        }
        return command.startNodeProcess(this@PlatformProcessClient)
    }

    actual override fun close() {
        cancel()
    }
}

private suspend fun ProcessCommand.startNodeProcess(ownerScope: CoroutineScope): ProcessSession {
    var acquired: NodeProcessSession? = null
    try {
        return suspendCancellableCoroutine { continuation ->
            val process = try {
                spawn(
                    executable,
                    arguments.toJsArray(),
                    unsafeJso<SpawnOptionsWithoutStdio> {
                        cwd = workingDirectory.toString()
                        shell = false
                        windowsHide = true
                        detached = !isWindowsNode
                        env = environment.toNodeEnvironmentOrNull()
                    },
                )
            } catch (failure: Throwable) {
                continuation.resumeWithException(
                    ProcessException("Failed to start Node.js child process with $executable.", failure),
                )
                return@suspendCancellableCoroutine
            }

            // Register ownership before the asynchronous spawn/return handoff.
            val session = NodeProcessSession(process, ownerScope)
            acquired = session
            process.on(EventType("spawn"), EventListener { _: Any? ->
                if (continuation.isActive) {
                    if (ownerScope.isActive) {
                        continuation.resume(session) { _, unclaimed, _ -> unclaimed.close() }
                    } else {
                        session.close()
                        continuation.resumeWithException(CancellationException("Process client was cancelled during spawn."))
                    }
                } else {
                    session.close()
                }
            })
            process.on(EventType("error"), EventListener { error: Any? ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        ProcessException("Failed to start Node.js child process with $executable: $error"),
                    )
                }
            })
            continuation.invokeOnCancellation {
                session.close()
            }
        }
    } catch (primary: Throwable) {
        withContext(NonCancellable) {
            try {
                acquired?.closeAndJoin()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
        throw primary
    }
}

@OptIn(ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
private class NodeProcessSession(
    private val process: ChildProcessWithoutNullStreams,
    ownerScope: CoroutineScope,
) : ProcessSession {
    private val processStdin = NodeProcessRawSink(process.requiredStdin, ownerScope)
    private val processStdout = NodeProcessRawSource(process.requiredStdout, ownerScope)
    private val processStderr = NodeProcessRawSource(process.requiredStderr, ownerScope)
    override val stdin: CoroutineRawSink = processStdin
    override val stdout: CoroutineRawSource = processStdout
    override val stderr: CoroutineRawSource = processStderr
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val processClosed = CompletableDeferred<Unit>()
    private val releaseResult = CompletableDeferred<Unit>()
    private var primaryFailure: Throwable? = null
    private val cancellationGuard = ownerScope.launch(start = CoroutineStart.ATOMIC) {
        try {
            awaitCancellation()
        } catch (failure: CancellationException) {
            if (primaryFailure == null) primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                releaseProcess()
            }
        }
    }

    init {
        process.on(EventType("error"), EventListener { error: Any? ->
            fail(ProcessException("Node.js child process failed: $error"))
        })
        process.on(EventType("close"), EventListener { code: Any?, _: Any? ->
            processStdout.finishFromProcess()
            processStderr.finishFromProcess()
            exitCode.complete((code as? Number)?.toInt() ?: 1)
            processClosed.complete(Unit)
        })
        // A naturally exited process retains readable buffered output until
        // explicit session/client close, so the stream owner must remain alive.
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        processStdin.invalidate()
        processStdout.invalidate()
        processStderr.invalidate()
        cancellationGuard.cancel()
    }

    override suspend fun closeAndJoin(): Unit = withContext(NonCancellable) {
        close()
        // Original guard owns the actual stream destroy/terminate/close wait.
        // Never join the whole client, which may own active sibling sessions.
        kotlinx.coroutines.withTimeout(7.seconds) {
            cancellationGuard.join()
            releaseResult.await()
        }
    }

    private fun fail(failure: Throwable) {
        if (!exitCode.completeExceptionally(failure)) return
        primaryFailure = failure
        cancellationGuard.cancel()
    }

    private suspend fun releaseProcess() {
        closed.store(true)
        var cleanupFailure: Throwable? = null
        fun record(failure: Throwable) {
            val first = cleanupFailure
            if (first == null) cleanupFailure = failure
            else if (first !== failure) first.addSuppressed(failure)
        }
        // Each resource is attempted even if another destroy/termination fails.
        for (closeStream in listOf(
            processStdin::closeImmediately,
            processStdout::closeImmediately,
            processStderr::closeImmediately,
        )) {
            try {
                closeStream()
            } catch (failure: Throwable) {
                record(failure)
            }
        }
        if (!processClosed.isCompleted) {
            try {
                terminateNodeProcessTree(process)
            } catch (failure: Throwable) {
                record(failure)
            }
            try {
                if (withTimeoutOrNull(5.seconds) { processClosed.await() } == null) {
                    try {
                        process.kill(PosixSigkill)
                    } catch (failure: Throwable) {
                        record(failure)
                    }
                    if (withTimeoutOrNull(1.seconds) { processClosed.await() } == null) {
                        throw ProcessException("Node.js child did not report close after forced termination.")
                    }
                }
            } catch (failure: Throwable) {
                record(failure)
            }
        }
        val failure = cleanupFailure
        if (failure == null) {
            releaseResult.complete(Unit)
        } else {
            releaseResult.completeExceptionally(failure)
            val primary = primaryFailure
            if (primary != null && primary !== failure) primary.addSuppressed(failure)
            exitCode.completeExceptionally(primary ?: failure)
            if (primary == null) throw failure
        }
    }
}

private suspend fun terminateNodeProcessTree(process: ChildProcessWithoutNullStreams) {
    val pid = process.pid ?: return
    if (isWindowsNode) {
        terminateWindowsProcessTree(pid, process::kill)
        return
    }
    try {
        if (currentNodeProcess.kill(-pid, PosixSigkill)) return
    } catch (_: Throwable) {
        // Fall through to the direct child when its process group is already gone.
    }
    process.kill(PosixSigkill)
}

private suspend fun terminateWindowsProcessTree(
    pid: Double,
    fallback: () -> Unit,
): Unit {
    val taskKill = try {
        spawn(
            "taskkill",
            listOf("/PID", pid.toInt().toString(), "/T", "/F").toJsArray(),
        )
    } catch (_: Throwable) {
        fallback()
        return
    }
    val finished = CompletableDeferred<Unit>()
    val helperClosed = CompletableDeferred<Unit>()
    taskKill.on(EventType("close"), EventListener { _: Any?, _: Any? ->
        helperClosed.complete(Unit)
        finished.complete(Unit)
    })
    taskKill.on(EventType("error"), EventListener { _: Any? -> finished.complete(Unit) })
    var primary: Throwable? = null
    try {
        if (withTimeoutOrNull(3.seconds) { finished.await() } == null || taskKill.exitCode != 0.0) {
            fallback()
        }
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        withContext(NonCancellable) {
            var cleanupFailure: Throwable? = null
            fun attempt(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure
                    else if (first !== failure) first.addSuppressed(failure)
                }
            }
            if (!helperClosed.isCompleted) attempt { taskKill.kill(PosixSigkill) }
            attempt { taskKill.stdin?.destroy() }
            attempt { taskKill.stdout?.destroy() }
            attempt { taskKill.stderr?.destroy() }
            if (withTimeoutOrNull(1.seconds) { helperClosed.await() } == null) {
                val failure = ProcessException("Node.js taskkill helper did not close after termination.")
                val first = cleanupFailure
                if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
            }
            cleanupFailure?.let { failure ->
                val original = primary
                if (original == null) throw failure
                if (original !== failure) original.addSuppressed(failure)
            }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class NodeProcessRawSource(
    private val stream: node.stream.Readable,
    private val ownerScope: CoroutineScope,
) : CoroutineRawSource {
    private val chunks = Channel<ByteArray>(capacity = 1)
    private val closed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var currentChunk: ByteArray? = null
    private var currentOffset: Int = 0

    init {
        stream.on(EventType("data"), EventListener { chunk: Any? ->
            stream.pause()
            val bytes = chunk.toNodeBytes()
            if (bytes.isNotEmpty() && chunks.trySend(bytes).isFailure) {
                finish(IOException("Node.js process output exceeded the one-chunk backpressure boundary."))
            }
        })
        stream.on(EventType("end"), EventListener { _: Any? -> finish() })
        stream.on(EventType("close"), EventListener { _: Any? -> finish() })
        stream.on(EventType("error"), EventListener { error: Any? ->
            finish(IOException("Failed to read Node.js process output: $error"))
        })
        stream.pause()
    }

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed.load() && ownerScope.isActive) { "Process output is closed." }
        if (byteCount == 0L) return 0L

        val chunk = currentChunk ?: receiveChunk() ?: return -1L
        val count = minOf(byteCount, (chunk.size - currentOffset).toLong()).toInt()
        sink.write(chunk, startIndex = currentOffset, endIndex = currentOffset + count)
        currentOffset += count
        if (currentOffset == chunk.size) {
            currentChunk = null
            currentOffset = 0
            resumeIfOpen()
        }
        return count.toLong()
    }

    override suspend fun close() {
        closeImmediately()
    }

    fun closeImmediately() {
        invalidate()
        if (!stream.destroyed) stream.destroy()
    }

    fun invalidate() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        currentChunk = null
        currentOffset = 0
        chunks.cancel(CancellationException("Process output is closed."))
    }

    fun finishFromProcess() {
        finish()
    }

    private suspend fun receiveChunk(): ByteArray? {
        resumeIfOpen()
        val result = chunks.receiveCatching()
        check(!closed.load() && ownerScope.isActive) { "Process output is closed." }
        result.exceptionOrNull()?.let { throw it.asProcessIOException("Failed to read Node.js process output.") }
        val chunk = result.getOrNull() ?: return null
        currentChunk = chunk
        currentOffset = 0
        return chunk
    }

    private fun resumeIfOpen() {
        if (!closed.load() && !finished.load()) {
            stream.resume()
        }
    }

    private fun finish(cause: Throwable? = null) {
        if (finished.compareAndSet(expectedValue = false, newValue = true)) {
            chunks.close(cause)
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class NodeProcessRawSink(
    private val stream: node.stream.Writable,
    private val ownerScope: CoroutineScope,
) : CoroutineRawSink {
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var failure: IOException? = null

    init {
        stream.on(EventType("error"), EventListener { error: Any? ->
            failure = IOException("Failed to write Node.js process input: $error")
        })
    }

    override suspend fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        writeMutex.withLock {
            checkOpen()
            var remaining = byteCount
            while (remaining > 0L) {
                val count = minOf(remaining, NodeProcessIoChunkSize.toLong()).toInt()
                val bytes = source.readByteArray(count)
                writeChunk(bytes)
                remaining -= count
            }
        }
    }

    override suspend fun flush() {
        checkOpen()
    }

    override suspend fun close() {
        writeMutex.withLock {
            if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
            failure?.let { throw it }
            if (stream.destroyed || stream.writableEnded) return
            suspendCancellableCoroutine { continuation ->
                try {
                    stream.end {
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                } catch (error: Throwable) {
                    continuation.resumeWithException(
                        error.asProcessIOException("Failed to close Node.js process input."),
                    )
                }
            }
        }
    }

    fun closeImmediately() {
        invalidate()
        // Graceful stdin.end may already have marked it closed without destroying.
        if (!stream.destroyed) stream.destroy()
    }

    fun invalidate() {
        closed.store(true)
    }

    private suspend fun writeChunk(bytes: ByteArray): Unit = suspendCancellableCoroutine { continuation ->
        try {
            stream.write(bytes.toUint8Array()) { error ->
                if (!continuation.isActive) return@write
                if (error == null) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        IOException("Failed to write Node.js process input: $error"),
                    )
                }
            }
        } catch (error: Throwable) {
            continuation.resumeWithException(
                error.asProcessIOException("Failed to write Node.js process input."),
            )
        }
    }

    private fun checkOpen() {
        check(!closed.load() && ownerScope.isActive) { "Process input is closed." }
        failure?.let { throw it }
    }
}

private fun Any?.toNodeBytes(): ByteArray =
    when (this) {
        is String -> encodeToByteArray()
        is NodeBuffer<*> -> toByteArray()
        else -> toString().encodeToByteArray()
    }

private fun Map<String, String>.toNodeEnvironmentOrNull(): ProcessEnv? {
    if (isEmpty()) return null
    val result = Object.assign(unsafeJso<ProcessEnv>(), currentNodeProcess.env)
    val inheritedNames = if (isWindowsNode) {
        @Suppress("UNCHECKED_CAST")
        val names = js("Object.keys(result)") as Array<String>
        names.associateBy(String::lowercase)
    } else {
        emptyMap()
    }
    forEach { (name, value) ->
        result[inheritedNames[name.lowercase()] ?: name] = value
    }
    return result
}

private fun Throwable.asProcessIOException(message: String): IOException {
    if (this is CancellationException) throw this
    if (this is IOException) return this
    return IOException(message, this)
}
