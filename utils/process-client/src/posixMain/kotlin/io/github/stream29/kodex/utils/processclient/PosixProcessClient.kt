@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.processclient.cinterop.kodex_spawn_process
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_write
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import platform.posix.ECHILD
import platform.posix.EINTR
import platform.posix.ESRCH
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_SETFD
import platform.posix.SIGKILL
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.kill
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public actual class ProcessClient internal actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    AutoCloseable {

    public actual suspend fun start(command: ProcessCommand): ProcessSession =
        withContext(PosixProcessIoDispatcher) {
            this@ProcessClient.requireOpen()
            command.startPosixProcess(this@ProcessClient)
        }

    public actual override fun close() {
        cancel()
    }
}

private val PosixProcessIoDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(Int.MAX_VALUE, "Kodex.ProcessClientIO")

private fun ProcessCommand.startPosixProcess(ownerScope: CoroutineScope): ProcessSession =
    withPosixPipe { stdinRead, stdinWrite ->
        withPosixPipe { outputRead, outputWrite ->
            withPosixPipe { errorRead, errorWrite ->
                arguments.withPosixStrings { argumentValues, argumentCount ->
                    environment.entries.map { (name, value) -> "$name=$value" }
                        .withPosixStrings { environmentValues, environmentCount ->
                            memScoped {
                                val pid = alloc<IntVar>()
                                val processGroup = alloc<IntVar>()
                                val result = kodex_spawn_process(
                                    pid = pid.ptr,
                                    process_group = processGroup.ptr,
                                    stdin_read = stdinRead,
                                    stdin_write = stdinWrite,
                                    output_read = outputRead,
                                    output_write = outputWrite,
                                    error_read = errorRead,
                                    error_write = errorWrite,
                                    executable = executable,
                                    arguments = argumentValues,
                                    argument_count = argumentCount,
                                    environment_overrides = environmentValues,
                                    environment_override_count = environmentCount,
                                    working_directory = workingDirectory.toString(),
                                )
                                if (result != 0) {
                                    throw ProcessException(
                                        "Failed to start process with $executable: error $result.",
                                    )
                                }
                                PosixPipeTransfer(
                                    value = PosixProcessSession(
                                        pid = pid.value,
                                        ownsProcessGroup = processGroup.value != 0,
                                        stdinDescriptor = PosixDescriptor(stdinWrite),
                                        stdoutDescriptor = PosixDescriptor(outputRead),
                                        stderrDescriptor = PosixDescriptor(errorRead),
                                        ownerScope = ownerScope,
                                    ),
                                    transferRead = true,
                                )
                            }
                        }
                }
            }.let { session -> PosixPipeTransfer(session, transferRead = true) }
        }.let { session -> PosixPipeTransfer(session, transferWrite = true) }
    }

private inline fun <T> List<String>.withPosixStrings(
    block: (values: CPointer<CPointerVar<ByteVar>>?, count: ULong) -> T,
): T {
    if (isEmpty()) return block(null, 0uL)
    return memScoped {
        val values = allocArray<CPointerVar<ByteVar>>(size)
        forEachIndexed { index, value ->
            val encoded = value.encodeToByteArray()
            val text = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { byteIndex, byte -> text[byteIndex] = byte }
            text[encoded.size] = 0
            values[index] = text
        }
        block(values, size.toULong())
    }
}

private data class PosixPipeTransfer<T>(
    val value: T,
    val transferRead: Boolean = false,
    val transferWrite: Boolean = false,
)

private fun <T> withPosixPipe(block: (read: Int, write: Int) -> PosixPipeTransfer<T>): T = memScoped {
    val descriptors = allocArray<IntVar>(2)
    checkPosixResult(pipe(descriptors), "create process pipe")
    val read = descriptors[0]
    val write = descriptors[1]
    try {
        read.setCloseOnExec()
        write.setCloseOnExec()
        val transfer = block(read, write)
        if (!transfer.transferRead) close(read)
        if (!transfer.transferWrite) close(write)
        transfer.value
    } catch (failure: Throwable) {
        close(read)
        close(write)
        throw failure
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class PosixProcessSession(
    private val pid: Int,
    private val ownsProcessGroup: Boolean,
    stdinDescriptor: PosixDescriptor,
    stdoutDescriptor: PosixDescriptor,
    stderrDescriptor: PosixDescriptor,
    ownerScope: CoroutineScope,
) : ProcessSession {
    private val processStdin = BlockingCoroutineRawSink(
        PosixRawSink(stdinDescriptor),
        PosixProcessIoDispatcher,
    )
    private val processStdout = BlockingCoroutineRawSource(
        PosixRawSource(stdoutDescriptor),
        PosixProcessIoDispatcher,
    )
    private val processStderr = BlockingCoroutineRawSource(
        PosixRawSource(stderrDescriptor),
        PosixProcessIoDispatcher,
    )
    override val stdin: CoroutineRawSink = processStdin
    override val stdout: CoroutineRawSource = processStdout
    override val stderr: CoroutineRawSource = processStderr
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val cancellationGuard = ownerScope.lazyProcessCancellationGuard(::close)

    init {
        cancellationGuard.start()
        ownerScope.launch(PosixProcessIoDispatcher) {
            try {
                exitCode.complete(awaitExitCode())
            } catch (failure: Throwable) {
                exitCode.completeExceptionally(failure)
            }
        }.invokeOnCompletion { failure ->
            if (failure != null) close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        cancellationGuard.cancel()
        if (!exitCode.isCompleted) {
            if (!ownsProcessGroup || (kill(-pid, SIGKILL) != 0 && errno != ESRCH)) {
                if (kill(pid, SIGKILL) != 0 && errno != ESRCH) {
                    exitCode.completeExceptionally(
                        ProcessException("Failed to terminate process $pid: errno $errno."),
                    )
                }
            }
        }
        processStdin.closeImmediately()
        processStdout.closeImmediately()
        processStderr.closeImmediately()
    }

    private fun awaitExitCode(): Int = memScoped {
        val status = alloc<IntVar>()
        while (true) {
            when (waitpid(pid, status.ptr, 0)) {
                pid -> return@memScoped decodeExitCode(status.value)
                -1 -> when (errno) {
                    EINTR -> continue
                    ECHILD -> return@memScoped 0
                    else -> throw ProcessException("Failed to observe process exit: errno $errno.")
                }
            }
        }
        error("Unreachable")
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class PosixDescriptor(val value: Int) {
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean
        get() = !closed.load()

    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            close(value)
        }
    }
}

private class PosixRawSource(
    private val descriptor: PosixDescriptor,
) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount must not be negative." }
        if (byteCount == 0L) return 0L
        if (!descriptor.isOpen) throw IOException("Process output is closed.")

        val bytes = ByteArray(minOf(byteCount, ProcessIoChunkSize).toInt())
        while (true) {
            val count = bytes.usePinned { pinned ->
                read(descriptor.value, pinned.addressOf(0), bytes.size.toULong())
            }
            when {
                count > 0L -> {
                    sink.write(bytes, startIndex = 0, endIndex = count.toInt())
                    return count
                }

                count == 0L -> return -1L
                errno != EINTR -> throw IOException("Failed to read process output: errno $errno.")
            }
        }
    }

    override fun close() {
        descriptor.close()
    }
}

private class PosixRawSink(
    private val descriptor: PosixDescriptor,
) : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L && source.size >= byteCount) {
            "byteCount must be within the source buffer."
        }
        var remaining = byteCount
        while (remaining > 0L) {
            val bytes = source.readByteArray(minOf(remaining, ProcessIoChunkSize).toInt())
            var offset = 0
            while (offset < bytes.size) {
                if (!descriptor.isOpen) throw IOException("Process input is closed.")
                val count = bytes.usePinned { pinned ->
                    kodex_process_write(
                        descriptor.value,
                        pinned.addressOf(offset),
                        (bytes.size - offset).toULong(),
                    )
                }
                when {
                    count > 0L -> offset += count.toInt()
                    errno != EINTR -> throw IOException("Failed to write process input: errno $errno.")
                }
            }
            remaining -= bytes.size
        }
    }

    override fun flush() = Unit

    override fun close() {
        descriptor.close()
    }
}

private fun Int.setCloseOnExec() {
    val flags = fcntl(this, F_GETFD)
    if (flags == -1) {
        throw ProcessException("Failed to read process descriptor flags: errno $errno.")
    }
    checkPosixResult(fcntl(this, F_SETFD, flags or FD_CLOEXEC), "set process descriptor close-on-exec")
}

private fun checkPosixResult(result: Int, operation: String) {
    if (result == -1) {
        throw ProcessException("Failed to $operation: errno $errno.")
    }
}

private fun decodeExitCode(status: Int): Int {
    val signal = status and 0x7f
    return if (signal == 0) (status ushr 8) and 0xff else 128 + signal
}

private const val ProcessIoChunkSize: Long = 8_192L
