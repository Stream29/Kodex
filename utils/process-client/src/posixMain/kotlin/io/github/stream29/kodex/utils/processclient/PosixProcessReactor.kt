@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    io.github.stream29.kodex.utils.processclient.NativeProcessAdapterApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.processclient.cinterop.KODEX_POLL_ERROR
import io.github.stream29.kodex.utils.processclient.cinterop.KODEX_POLL_HANGUP
import io.github.stream29.kodex.utils.processclient.cinterop.KODEX_POLL_INVALID
import io.github.stream29.kodex.utils.processclient.cinterop.KODEX_POLL_READ
import io.github.stream29.kodex.utils.processclient.cinterop.KODEX_POLL_WRITE
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_configure_stream
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_create_wake_pipe
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_drain_wake
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_next_exit_event
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_open_exit_queue
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_open_pidfd
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_poll
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_pty_eio_is_eof
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_reap
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_unwatch_kqueue
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_wake
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_watch_kqueue
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_write
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EIO
import platform.posix.ESRCH
import platform.posix.EWOULDBLOCK
import platform.posix.SIGKILL
import platform.posix.close
import platform.posix.errno
import platform.posix.kill
import platform.posix.read
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@NativeProcessAdapterApi
public actual fun adoptNativeProcessStream(handle: Long, terminal: Boolean): NativeProcessStream {
    require(handle in 0..Int.MAX_VALUE.toLong()) { "Expected a POSIX file descriptor." }
    val reactor = posixProcessReactor
    val fd = handle.toInt()
    return reactor.adopt(fd, terminal && kodex_process_pty_eio_is_eof() != 0)
}

@NativeProcessAdapterApi
public actual fun watchNativeProcessExit(process: Long, processTree: Long): NativeProcessExit {
    require(process in 1..Int.MAX_VALUE.toLong() && processTree in 0..Int.MAX_VALUE.toLong()) {
        "Expected a POSIX child PID and an optional process group ID."
    }
    val reactor = posixProcessReactor
    return PosixExit(reactor, process.toInt(), processTree.toInt()).also { child ->
        reactor.submit { register(child) }
    }
}

private val posixProcessReactor by lazy { PosixProcessReactor.start() }
private const val ProcessChunkSize = 8_192L

private class PosixStream(
    private val reactor: PosixProcessReactor,
    val fd: Int,
    val terminalEioIsEof: Boolean,
) : NativeReadyProcessStream {
    private val closeRequested = AtomicBoolean(false)
    private val finishReadsRequested = AtomicBoolean(false)
    val released = CompletableDeferred<Unit>()
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    // The reactor thread alone reads/writes these slots. A snapshot holds their identities, not just fd.
    var reader: StreamRequest? = null
    var writer: StreamRequest? = null
    var eof = false

    val isClosing: Boolean get() = closeRequested.load()
    val isFinishingReads: Boolean get() = finishReadsRequested.load()

    override fun requestClose() {
        if (closeRequested.compareAndSet(false, true)) reactor.submit { retire(this@PosixStream) }
    }

    override suspend fun close() {
        requestClose()
        withContext(NonCancellable) { released.await() }
    }

    override fun finishReadsWithoutWaiting() {
        if (finishReadsRequested.compareAndSet(false, true)) {
            reactor.submit { retryRead(this@PosixStream) }
        }
    }

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = readMutex.withLock {
        require(byteCount >= 0L) { "byteCount must not be negative." }
        if (byteCount == 0L) return@withLock 0L
        val bytes = ByteArray(minOf(byteCount, ProcessChunkSize).toInt())
        val count = transfer(bytes, isRead = true)
        if (count > 0) sink.write(bytes, startIndex = 0, endIndex = count)
        count.toLong()
    }

    override suspend fun write(source: Buffer, byteCount: Long): Unit = writeMutex.withLock {
        require(byteCount >= 0L && source.size >= byteCount) { "byteCount must be within the source buffer." }
        var remaining = byteCount
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            if (isClosing) throw closedStream()
            val bytes = source.readByteArray(minOf(remaining, ProcessChunkSize).toInt())
            var offset = 0
            while (offset < bytes.size) {
                offset += transfer(bytes, isRead = false, offset = offset)
            }
            remaining -= bytes.size
        }
    }

    override suspend fun flush() {
        if (isClosing) throw closedStream()
    }

    private suspend fun transfer(bytes: ByteArray, isRead: Boolean, offset: Int = 0): Int =
        suspendCancellableCoroutine { continuation ->
            val request = StreamRequest(this, bytes, offset, isRead, continuation)
            continuation.invokeOnCancellation { reactor.submit { detach(request) } }
            reactor.submit { start(request) }
        }
}

private class StreamRequest(
    val stream: PosixStream,
    val bytes: ByteArray,
    val offset: Int,
    val isRead: Boolean,
    val continuation: CancellableContinuation<Int>,
)

private class PosixExit(
    private val reactor: PosixProcessReactor,
    val pid: Int,
    val pgid: Int,
) : NativeProcessExit {
    val status = CompletableDeferred<Int>()
    val released = CompletableDeferred<Unit>()
    private val terminationRequested = AtomicBoolean(false)

    // Reactor-thread state.
    var token = 0L
    var pidfd = -1
    var kqueueRegistered = false
    var terminationFailure: ProcessException? = null

    override suspend fun await(): Int = status.await()

    override fun terminate() {
        if (terminationRequested.compareAndSet(false, true)) reactor.submit { terminate(this@PosixExit) }
    }

    override suspend fun close() {
        withContext(NonCancellable) { released.await() }
    }
}

private class PosixProcessReactor(
    private val wakeRead: Int,
    private val wakeWrite: Int,
) {
    private val commands = Channel<PosixProcessReactor.() -> Unit>(Channel.UNLIMITED)
    private val reservedDescriptors = AtomicReference<Set<Int>>(emptySet())
    private val streams = mutableMapOf<Int, PosixStream>()
    private val activeStreams = mutableSetOf<PosixStream>()
    private val children = mutableMapOf<Int, PosixExit>()
    private val kqueueChildren = mutableMapOf<Long, PosixExit>()
    private val fallback = mutableSetOf<PosixExit>()
    private val exitQueue by lazy { kodex_process_open_exit_queue() }
    private var exitQueueUsable = true
    private var nextToken = 1L
    private var fallbackIntervalMillis = 4
    private var fallbackDeadline: TimeMark? = null

    fun submit(command: PosixProcessReactor.() -> Unit) {
        commands.trySend(command)
        kodex_process_wake(wakeWrite)
    }

    fun adopt(fd: Int, terminalEioIsEof: Boolean): PosixStream {
        val stream = PosixStream(this, fd, terminalEioIsEof)
        while (true) {
            val current = reservedDescriptors.load()
            if (fd in current) throw IOException("Process descriptor $fd already has an owner.")
            if (reservedDescriptors.compareAndSet(current, current + fd)) break
        }
        // No ownership transfer until configuration succeeds. The caller still owns fd here.
        if (kodex_process_configure_stream(fd) != 0) {
            val error = errno
            releaseReservation(fd)
            throw IOException("Failed to configure process descriptor $fd: errno $error.")
        }
        submit { streams[fd] = stream }
        return stream
    }

    private fun releaseReservation(fd: Int) {
        while (true) {
            val current = reservedDescriptors.load()
            if (reservedDescriptors.compareAndSet(current, current - fd)) return
        }
    }

    fun retire(stream: PosixStream) {
        if (streams[stream.fd] !== stream) return
        // Remove the registration before close can make this fd number reusable.
        streams.remove(stream.fd)
        val reader = stream.reader
        val writer = stream.writer
        reader?.let(::detach)
        writer?.let(::detach)
        releaseReservation(stream.fd)
        // Never retry close: on some targets EINTR can still release the fd.
        val error = if (close(stream.fd) == 0) 0 else errno
        reader?.let { publish { it.continuation.resumeWith(Result.failure(closedStream())) } }
        writer?.let { publish { it.continuation.resumeWith(Result.failure(closedStream())) } }
        publish {
            if (error == 0) stream.released.complete(Unit)
            else stream.released.completeExceptionally(IOException("Failed to close process descriptor ${stream.fd}: errno $error."))
        }
    }

    fun start(request: StreamRequest) {
        if (!request.continuation.isActive) return
        val stream = request.stream
        if (stream.isClosing || streams[stream.fd] !== stream) {
            publish { request.continuation.resumeWith(Result.failure(closedStream())) }
            return
        }
        if (request.isRead) stream.reader = request else stream.writer = request
        activeStreams.add(stream)
        transfer(request)
    }

    fun detach(request: StreamRequest) {
        val stream = request.stream
        if (stream.reader === request) stream.reader = null
        if (stream.writer === request) stream.writer = null
        if (stream.reader == null && stream.writer == null) activeStreams.remove(stream)
    }

    fun retryRead(stream: PosixStream) {
        if (streams[stream.fd] === stream) stream.reader?.let(::transfer)
    }

    private fun transfer(request: StreamRequest) {
        val stream = request.stream
        if (!request.continuation.isActive) {
            detach(request)
            return
        }
        val outcome = when {
            stream.isClosing -> Result.failure(closedStream())
            request.isRead && stream.eof -> Result.success(-1)
            else -> tryTransfer(request) ?: return
        }
        if (request.isRead && outcome.getOrNull() == -1) stream.eof = true
        detach(request)
        publish { request.continuation.resumeWith(outcome) }
    }

    private fun tryTransfer(request: StreamRequest): Result<Int>? {
        val stream = request.stream
        while (true) {
            var error = 0
            val count = request.bytes.usePinned { bytes ->
                val address = bytes.addressOf(request.offset)
                val remaining = (request.bytes.size - request.offset).toULong()
                val result = if (request.isRead) read(stream.fd, address, remaining)
                else kodex_process_write(stream.fd, address, remaining)
                if (result < 0L) error = errno
                result
            }
            if (count > 0L) return Result.success(count.toInt())
            if (count == 0L && request.isRead) return Result.success(-1)
            if (count < 0L) {
                when {
                    error == EINTR -> {
                        if (stream.isClosing || !request.continuation.isActive) return Result.failure(closedStream())
                        continue
                    }
                    error == EAGAIN || error == EWOULDBLOCK ->
                        return if (request.isRead && stream.isFinishingReads) Result.success(-1) else null
                    error == EIO && request.isRead && stream.terminalEioIsEof -> return Result.success(-1)
                }
            }
            val operation = if (request.isRead) "read" else "write"
            return Result.failure(IOException("Failed to $operation process descriptor ${stream.fd}: errno $error."))
        }
    }

    fun register(child: PosixExit) {
        if (children.containsKey(child.pid)) {
            publish {
                child.status.completeExceptionally(ProcessException("PID ${child.pid} already has an exit watcher."))
            }
            publish { child.released.complete(Unit) }
            return
        }
        children[child.pid] = child
        child.token = nextToken++
        // Verify that we own this unreaped child before arming notifications (or accepting a kill).
        if (reap(child)) return
        child.pidfd = kodex_process_open_pidfd(child.pid)
        if (child.pidfd < 0 && exitQueueUsable && exitQueue >= 0) {
            child.kqueueRegistered = kodex_process_watch_kqueue(exitQueue, child.pid, child.token) == 0
            if (child.kqueueRegistered) kqueueChildren[child.token] = child
            else kodex_process_unwatch_kqueue(exitQueue, child.pid)
        }
        // Covers the exit race between the first waitpid and NOTE_EXIT registration.
        if (reap(child)) return
        if (child.pidfd < 0 && !child.kqueueRegistered) useFallback(child)
    }

    fun terminate(child: PosixExit) {
        // waitpid and kill both run here. Once reaped, this token can never kill a reused PID.
        if (children[child.pid] !== child) return
        if (child.pgid > 0) killRetryingInterrupts(-child.pgid)
        if (killRetryingInterrupts(child.pid) != 0 && errno != ESRCH) {
            child.terminationFailure = ProcessException("Failed to terminate process ${child.pid}: errno $errno.")
        }
        if (child in fallback) fallbackDeadline = TimeSource.Monotonic.markNow()
    }

    private fun reap(child: PosixExit): Boolean = memScoped {
        val code = alloc<IntVar>()
        val result = kodex_process_reap(child.pid, code.ptr)
        val error = if (result < 0) errno else 0
        if (result == 0) return@memScoped false
        val status = if (result > 0) Result.success(code.value)
        else Result.failure(ProcessException("Failed to reap process ${child.pid}: errno $error."))
        children.remove(child.pid)
        fallback.remove(child)
        if (fallback.isEmpty()) fallbackDeadline = null
        removeExitWatch(child)
        publish { status.fold(child.status::complete, child.status::completeExceptionally) }
        publish {
            val failure = child.terminationFailure
            if (failure == null) child.released.complete(Unit) else child.released.completeExceptionally(failure)
        }
        true
    }

    private fun removeExitWatch(child: PosixExit) {
        if (child.pidfd >= 0) {
            val fd = child.pidfd
            child.pidfd = -1
            close(fd)
        }
        if (child.kqueueRegistered) {
            child.kqueueRegistered = false
            kqueueChildren.remove(child.token)
            if (exitQueueUsable) kodex_process_unwatch_kqueue(exitQueue, child.pid)
        }
    }

    private fun disableExitQueue(invalid: Boolean) {
        exitQueueUsable = false
        for (child in kqueueChildren.values.toList()) useFallback(child)
        if (!invalid) close(exitQueue)
    }

    private fun useFallback(child: PosixExit) {
        removeExitWatch(child)
        if (fallback.add(child) && fallbackDeadline == null) {
            fallbackIntervalMillis = 4
            fallbackDeadline = TimeSource.Monotonic.markNow() + fallbackIntervalMillis.milliseconds
        }
    }

    private fun runFallbackIfDue() {
        if (fallbackDeadline?.hasPassedNow() == true) {
            for (child in fallback.toList()) reap(child)
            fallbackIntervalMillis = minOf(fallbackIntervalMillis * 2, 64)
            fallbackDeadline = if (fallback.isEmpty()) null
            else TimeSource.Monotonic.markNow() + fallbackIntervalMillis.milliseconds
        }
    }

    private fun snapshot(): List<PollTarget> = buildList {
        add(PollTarget(wakeRead, KODEX_POLL_READ))
        if (kqueueChildren.isNotEmpty()) add(PollTarget(exitQueue, KODEX_POLL_READ))
        for (child in children.values) {
            if (child.pidfd >= 0) add(PollTarget(child.pidfd, KODEX_POLL_READ, child = child))
        }
        for (stream in activeStreams) {
            val reader = stream.reader
            val writer = stream.writer
            val events = (if (reader != null) KODEX_POLL_READ else 0) or
                (if (writer != null) KODEX_POLL_WRITE else 0)
            add(PollTarget(stream.fd, events, reader = reader, writer = writer))
        }
    }

    private fun dispatch(target: PollTarget, events: Int) {
        val exceptional = events and (KODEX_POLL_ERROR or KODEX_POLL_INVALID or KODEX_POLL_HANGUP) != 0
        when {
            target.fd == wakeRead -> kodex_process_drain_wake(wakeRead)
            target.child != null -> {
                val child = target.child
                if (children[child.pid] === child && child.pidfd == target.fd && !reap(child)) useFallback(child)
            }
            target.reader != null || target.writer != null -> {
                // A pipe may close before its root exits. Only accelerate the fallback
                // sweep here; waitpid remains the sole source of exit status.
                if (events and KODEX_POLL_HANGUP != 0 && fallback.isNotEmpty()) {
                    fallbackDeadline = TimeSource.Monotonic.markNow()
                }
                target.reader?.let {
                    if ((exceptional || events and KODEX_POLL_READ != 0) &&
                        streams[target.fd] === it.stream && it.stream.reader === it
                    ) transfer(it)
                }
                target.writer?.let {
                    if ((exceptional || events and KODEX_POLL_WRITE != 0) &&
                        streams[target.fd] === it.stream && it.stream.writer === it
                    ) transfer(it)
                }
            }
            else -> {
                // This is the shared kqueue fd. A one-shot event carries its registration token.
                if (exceptional) {
                    disableExitQueue(invalid = events and KODEX_POLL_INVALID != 0)
                    return
                }
                repeat(128) {
                    val token = kodex_process_next_exit_event(target.fd)
                    if (token < 0L) {
                        disableExitQueue(invalid = false)
                        return
                    }
                    if (token == 0L) return
                    val child = kqueueChildren[token]
                    if (child != null && !reap(child)) useFallback(child)
                }
            }
        }
    }

    private fun drainCommands(): Boolean {
        repeat(128) {
            val command = commands.tryReceive().getOrNull() ?: return false
            command()
        }
        return true
    }

    private fun run() {
        while (true) {
            val commandsRemain = drainCommands()
            runFallbackIfDue()
            val targets = snapshot()
            val timeout = when {
                commandsRemain -> 0
                fallback.isEmpty() -> -1
                else -> (-checkNotNull(fallbackDeadline).elapsedNow()).inWholeMilliseconds.toInt().coerceAtLeast(1)
            }
            memScoped {
                val rows = allocArray<IntVar>(targets.size * 3)
                targets.forEachIndexed { index, target ->
                    rows[index * 3] = target.fd
                    rows[index * 3 + 1] = target.events
                }
                val ready = kodex_process_poll(rows, targets.size, timeout)
                if (ready > 0) {
                    // Retire/cancel queued work before consulting any fd numbers from poll.
                    drainCommands()
                    targets.forEachIndexed { index, target ->
                        val events = rows[index * 3 + 2]
                        if (events != 0) dispatch(target, events)
                    }
                } else if (ready < 0 && errno != EINTR) {
                    // A transient poll allocation failure must not strand registered resources.
                    usleep(4_000u)
                }
            }
        }
    }

    private class PollTarget(
        val fd: Int,
        val events: Int,
        val child: PosixExit? = null,
        val reader: StreamRequest? = null,
        val writer: StreamRequest? = null,
    )

    companion object {
        fun start(): PosixProcessReactor = memScoped {
            val descriptors = allocArray<IntVar>(2)
            if (kodex_process_create_wake_pipe(descriptors) != 0) {
                throw ProcessException("Failed to create process reactor wake pipe: errno $errno.")
            }
            val reactor = PosixProcessReactor(descriptors[0], descriptors[1])
            try {
                val dispatcher = newSingleThreadContext("Kodex.PosixProcessReactor")
                CoroutineScope(dispatcher).launch { reactor.run() }
            } catch (failure: Throwable) {
                close(descriptors[0])
                close(descriptors[1])
                throw failure
            }
            reactor
        }
    }
}

private fun killRetryingInterrupts(pid: Int): Int {
    while (true) {
        val result = kill(pid, SIGKILL)
        if (result == 0 || errno != EINTR) return result
    }
}

private fun closedStream(): IOException = IOException("Process stream is closed.")

// Completion can resume an unconfined caller; never run caller code on the poll thread.
private fun publish(block: () -> Unit) {
    Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable { block() })
}
