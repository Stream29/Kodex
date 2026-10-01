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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

public actual class ProcessClient internal actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    AutoCloseable {

    public actual suspend fun start(command: ProcessCommand): ProcessSession {
        this@ProcessClient.requireOpen()
        if (command.executable.isBlank()) {
            throw ProcessException("Process executable must not be blank.")
        }
        return command.startNodeProcess(this@ProcessClient)
    }

    public actual override fun close() {
        cancel()
    }
}

private suspend fun ProcessCommand.startNodeProcess(ownerScope: CoroutineScope): ProcessSession =
    suspendCancellableCoroutine { continuation ->
        var session: NodeProcessSession? = null
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

        process.on(EventType("spawn"), EventListener { _: Any? ->
            val startedSession = NodeProcessSession(process, ownerScope)
            session = startedSession
            if (continuation.isActive) {
                continuation.resume(startedSession)
            } else {
                startedSession.close()
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
            session?.close() ?: process.kill()
        }
    }

@OptIn(ExperimentalAtomicApi::class)
private class NodeProcessSession(
    private val process: ChildProcessWithoutNullStreams,
    ownerScope: CoroutineScope,
) : ProcessSession {
    private val processStdin = NodeProcessRawSink(process.requiredStdin)
    private val processStdout = NodeProcessRawSource(process.requiredStdout)
    private val processStderr = NodeProcessRawSource(process.requiredStderr)
    override val stdin: CoroutineRawSink = processStdin
    override val stdout: CoroutineRawSource = processStdout
    override val stderr: CoroutineRawSource = processStderr
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cancellationGuard = ownerScope.lazyProcessCancellationGuard(::close)

    init {
        cancellationGuard.start()
        process.on(EventType("error"), EventListener { error: Any? ->
            fail(ProcessException("Node.js child process failed: $error"))
        })
        process.on(EventType("close"), EventListener { code: Any?, _: Any? ->
            processStdout.finishFromProcess()
            processStderr.finishFromProcess()
            if (exitCode.complete((code as? Number)?.toInt() ?: 1)) {
                cancellationGuard.cancel()
            }
        })
    }

    override fun close() {
        if (exitCode.isCompleted || !closed.compareAndSet(expectedValue = false, newValue = true)) return
        cancellationGuard.cancel()
        processStdin.closeImmediately()
        cleanupScope.launchTermination(process)
    }

    private fun fail(failure: Throwable) {
        if (!exitCode.completeExceptionally(failure)) return
        cancellationGuard.cancel()
        closeStreamsImmediately()
        cleanupScope.launchTermination(process)
    }

    private fun closeStreamsImmediately() {
        processStdin.closeImmediately()
        processStdout.closeImmediately()
        processStderr.closeImmediately()
    }
}

private fun CoroutineScope.launchTermination(process: ChildProcessWithoutNullStreams) {
    launch {
        terminateNodeProcessTree(process)
    }
}

private suspend fun terminateNodeProcessTree(process: ChildProcessWithoutNullStreams) {
    val pid = process.pid ?: return
    if (isWindowsNode) {
        if (withTimeoutOrNull(3.seconds) { terminateWindowsProcessTree(pid, process::kill) } == null) {
            process.kill()
        }
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
): Unit = suspendCancellableCoroutine { continuation ->
    fun finish() {
        if (continuation.isActive) {
            continuation.resume(Unit)
        }
    }
    try {
        spawn(
            "taskkill",
            listOf("/PID", pid.toInt().toString(), "/T", "/F").toJsArray(),
        ).also { taskKill ->
            taskKill.on(EventType("close"), EventListener { _: Any?, _: Any? -> finish() })
            taskKill.on(EventType("error"), EventListener { _: Any? ->
                fallback()
                finish()
            })
        }
    } catch (_: Throwable) {
        fallback()
        finish()
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class NodeProcessRawSource(
    private val stream: node.stream.Readable,
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
        check(!closed.load()) { "Process output is closed." }
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
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        stream.destroy()
        finish(IOException("Process output is closed."))
    }

    fun finishFromProcess() {
        finish()
    }

    private suspend fun receiveChunk(): ByteArray? {
        resumeIfOpen()
        val result = chunks.receiveCatching()
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
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            stream.destroy()
        }
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
        check(!closed.load()) { "Process input is closed." }
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
