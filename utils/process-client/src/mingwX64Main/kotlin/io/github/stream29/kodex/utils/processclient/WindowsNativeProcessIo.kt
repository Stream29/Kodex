@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.processclient.winspawn.kodex_associate_pipe
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_cancel_pipe_io
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_create_pipe_port
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_drain_process_wait
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_lock_process_watch
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_pipe_operation
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_register_process_wait
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_submit_pipe_io
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_take_pipe_completion
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_terminate_process_tree
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_unlock_process_watch
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import platform.windows.CloseHandle
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.ERROR_HANDLE_EOF
import platform.windows.ERROR_NO_DATA
import platform.windows.ERROR_OPERATION_ABORTED
import platform.windows.ERROR_PIPE_NOT_CONNECTED
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

@NativeProcessAdapterApi
public actual fun adoptNativeProcessStream(handle: Long, terminal: Boolean): NativeProcessStream {
    val pipe = requireNotNull(handle.toCPointer<COpaque>())
    val stream = WindowsPipeStream(pipe)
    val error = kodex_associate_pipe(WindowsPipePort.handle, pipe)
    if (error != 0u) throw IOException("Failed to associate process pipe with IOCP: error $error.")
    return stream
}

@NativeProcessAdapterApi
public actual fun watchNativeProcessExit(process: Long, processTree: Long): NativeProcessExit {
    val processHandle = requireNotNull(process.toCPointer<COpaque>())
    val jobHandle = processTree.toCPointer<COpaque>()
    return WindowsProcessWatch(processHandle, jobHandle).also { it.register() }
}

// One process-lifetime port and blocking loop. Deliver packets on Default so
// even an unconfined consumer (or one that closes a wait) cannot stall IOCP.
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private object WindowsPipePort {
    val handle: COpaquePointer = kodex_create_pipe_port()
        ?: throw IOException("Failed to create process IOCP: error ${GetLastError()}.")

    init {
        val dispatcher = try {
            newSingleThreadContext("Kodex.WindowsIOCP")
        } catch (failure: Throwable) {
            CloseHandle(handle)
            throw failure
        }
        CoroutineScope(dispatcher).launch { runCompletionLoop() }
    }

    private fun runCompletionLoop(): Unit = memScoped {
        val context = alloc<COpaquePointerVar>()
        val count = alloc<UIntVar>().apply { value = 0u }
        while (true) {
            val error = kodex_take_pipe_completion(handle, context.ptr, count.ptr)
            val raw = checkNotNull(context.value) { "Process IOCP failed: error $error." }
            val operation = raw.asStableRef<PipeOperation>().get()
            val result = PipeCompletion(count.value.toInt(), error)
            WindowsIoDelivery.launch { operation.stream.complete(operation, result) }
        }
    }
}

private val WindowsIoDelivery = CoroutineScope(Dispatchers.Default + SupervisorJob())
private val WindowsWaitDrain = Dispatchers.IO.limitedParallelism(4, "Kodex.WindowsWaitDrain")
private const val WindowsPipeChunk = 8_192

private data class PipeCompletion(val count: Int, val error: UInt)

@OptIn(NativeProcessAdapterApi::class)
private class WindowsPipeStream(private val handle: COpaquePointer) : NativeProcessStream {
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Result<Unit>>()
    private val state = Mutex()
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val pending = mutableSetOf<PipeOperation>()
    private var handleClosed = false

    override fun requestClose() {
        if (!closing.compareAndSet(false, true)) return
        WindowsIoDelivery.launch {
            val result = state.withLock {
                for (operation in pending) kodex_cancel_pipe_io(handle, operation.native.ptr)
                closeIfDrained()
            }
            result?.let { closed.complete(it) }
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        requestClose()
        closed.await().getOrThrow()
    }

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0L
        val buffer = ByteArray(minOf(byteCount, WindowsPipeChunk.toLong()).toInt())
        return readMutex.withLock {
            var result: PipeCompletion
            do {
                result = transfer(buffer, 0, reading = true)
                // A successful zero-byte write by the peer is not EOF.
            } while (result.error == 0u && result.count == 0)
            when (result.error) {
                0u -> {
                    sink.write(buffer, 0, result.count)
                    result.count.toLong()
                }
                ERROR_BROKEN_PIPE.toUInt(), ERROR_HANDLE_EOF.toUInt(),
                ERROR_NO_DATA.toUInt(), ERROR_PIPE_NOT_CONNECTED.toUInt() -> -1L
                else -> throw IOException("Failed to read process pipe: error ${result.error}.")
            }
        }
    }

    override suspend fun write(source: Buffer, byteCount: Long): Unit = writeMutex.withLock {
        require(byteCount >= 0 && byteCount <= source.size)
        var remaining = byteCount
        while (remaining != 0L) {
            currentCoroutineContext().ensureActive()
            checkOpen()
            val buffer = source.readByteArray(minOf(remaining, WindowsPipeChunk.toLong()).toInt())
            var offset = 0
            while (offset < buffer.size) {
                val result = transfer(buffer, offset, reading = false)
                if (result.error != 0u) throw IOException("Failed to write process pipe: error ${result.error}.")
                if (result.count == 0) throw IOException("Process pipe accepted no bytes.")
                offset += result.count
            }
            remaining -= buffer.size
        }
    }

    override suspend fun flush() {
        checkOpen()
    }

    private suspend fun transfer(buffer: ByteArray, offset: Int, reading: Boolean): PipeCompletion {
        currentCoroutineContext().ensureActive()
        val operation = state.withLock {
            checkOpen()
            val operation = PipeOperation.allocate(this, buffer)
            pending.add(operation)
            val error = kodex_submit_pipe_io(
                handle, operation.native.ptr, operation.reference.asCPointer(),
                operation.pinned.addressOf(offset), (buffer.size - offset).toUInt(), if (reading) 1 else 0,
            )
            if (error != 0u) {
                pending.remove(operation)
                operation.release()
                return evaluate(PipeCompletion(0, error)) // No IOCP packet on immediate failure.
            }
            operation
        }
        val result = try {
            operation.completed.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                state.withLock {
                    if (operation in pending) kodex_cancel_pipe_io(handle, operation.native.ptr)
                }
                operation.completed.await()
            }
            throw cancelled
        }
        return evaluate(result)
    }

    suspend fun complete(operation: PipeOperation, result: PipeCompletion) {
        val closeResult = state.withLock {
            check(pending.remove(operation))
            operation.release()
            closeIfDrained()
        }
        operation.completed.complete(result)
        closeResult?.let { closed.complete(it) }
    }

    private fun closeIfDrained(): Result<Unit>? {
        if (!closing.load() || pending.isNotEmpty() || handleClosed) return null
        handleClosed = true
        return if (CloseHandle(handle) != 0) Result.success(Unit)
        else Result.failure(IOException("Failed to close process pipe: error ${GetLastError()}."))
    }

    private fun checkOpen() {
        if (closing.load()) throw IOException("Process pipe is closed.")
    }

    private fun evaluate(result: PipeCompletion): PipeCompletion {
        checkOpen()
        // Keep cancellation distinct from the peer's end-of-stream.
        if (result.error == ERROR_OPERATION_ABORTED.toUInt()) throw IOException("Process pipe I/O was cancelled.")
        return result
    }
}

private class PipeOperation private constructor(
    val stream: WindowsPipeStream,
    val native: kodex_pipe_operation,
    val pinned: Pinned<ByteArray>,
) {
    val completed = CompletableDeferred<PipeCompletion>()
    val reference = StableRef.create(this)

    companion object {
        fun allocate(stream: WindowsPipeStream, buffer: ByteArray): PipeOperation {
            val native = nativeHeap.alloc<kodex_pipe_operation>()
            try {
                val pinned = buffer.pin()
                try {
                    return PipeOperation(stream, native, pinned)
                } catch (failure: Throwable) {
                    pinned.unpin()
                    throw failure
                }
            } catch (failure: Throwable) {
                nativeHeap.free(native.rawPtr)
                throw failure
            }
        }
    }

    // Called after the packet is dequeued or when submission failed before
    // queueing, never merely because CancelIoEx returned.
    fun release() {
        reference.dispose()
        pinned.unpin()
        nativeHeap.free(native.rawPtr)
    }
}

@OptIn(NativeProcessAdapterApi::class)
private class WindowsProcessWatch(private val process: COpaquePointer, private val job: COpaquePointer?) :
    NativeProcessExit {
    private val exit = CompletableDeferred<Int>()
    private val observed = AtomicReference<Result<Int>?>(null)
    private val drained = CompletableDeferred<Result<Unit>>()
    private var closing = false // Only accessed under the native watch lock.
    private lateinit var registration: COpaquePointer
    private var reference: StableRef<WindowsProcessWatch>? = null

    fun register() = memScoped {
        val wait = alloc<COpaquePointerVar>()
        val ref = StableRef.create(this@WindowsProcessWatch)
        val error = kodex_register_process_wait(wait.ptr, process, WindowsProcessWaitCallback, ref.asCPointer())
        if (error != 0u) {
            ref.dispose()
            throw ProcessException("Failed to register process exit wait: error $error.")
        }
        registration = requireNotNull(wait.value)
        reference = ref
    }

    override suspend fun await(): Int = exit.await()

    override fun terminate() = withWindowsWatchLock {
        if (closing) return@withWindowsWatchLock
        terminateWindowsProcessTree(process, job)
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        val firstClose = withWindowsWatchLock {
            if (closing) false else {
                closing = true
                true
            }
        }
        if (firstClose) {
            val result = runCatching {
                withContext(WindowsWaitDrain) {
                    val error = kodex_drain_process_wait(registration)
                    if (error != 0u) {
                        // No drain proof: keep the StableRef and tell the owner
                        // not to close the process/job borrowed by this callback.
                        throw ProcessException("Failed to drain process exit wait: error $error.")
                    }
                    checkNotNull(reference).dispose()
                    reference = null
                }
            }
            val observation = observed.load()
                ?: Result.failure(ProcessException("Process exit wait was closed."))
            publish(observation)
            drained.complete(result)
        }
        drained.await().getOrThrow()
    }

    fun onSignalled() {
        val result = withWindowsWatchLock {
            runCatching {
                memScoped {
                    val code = alloc<UIntVar>()
                    if (GetExitCodeProcess(process, code.ptr) == 0) {
                        throw ProcessException("Failed to read process exit code: error ${GetLastError()}.")
                    }
                    code.value.toInt()
                }
            }.also(observed::store)
        }
        // An unconfined awaiter must never run inside a callback that close()
        // will synchronously drain via UnregisterWaitEx.
        WindowsIoDelivery.launch { publish(result) }
    }

    private fun publish(result: Result<Int>) {
        result.fold(exit::complete, exit::completeExceptionally)
    }
}

private val WindowsProcessWaitCallback = staticCFunction { raw: COpaquePointer?, _: UByte ->
    requireNotNull(raw).asStableRef<WindowsProcessWatch>().get().onSignalled()
}

internal fun terminateWindowsProcessTree(process: COpaquePointer, job: COpaquePointer?) {
    val error = kodex_terminate_process_tree(process, job)
    if (error != 0u) throw ProcessException("Failed to terminate process tree: error $error.")
}

private inline fun <T> withWindowsWatchLock(block: () -> T): T {
    kodex_lock_process_watch()
    try {
        return block()
    } finally {
        kodex_unlock_process_watch()
    }
}
