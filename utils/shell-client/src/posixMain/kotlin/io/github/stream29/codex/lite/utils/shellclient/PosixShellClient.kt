@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.codex.lite.utils.shellclient

import io.github.stream29.codex.lite.utils.shellclient.cinterop.codexlite_spawn_shell
import io.github.stream29.codex.lite.utils.shellclient.cinterop.codexlite_spawn_pty_shell
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.EAGAIN
import platform.posix.ECHILD
import platform.posix.EIO
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SIGKILL
import platform.posix.WNOHANG
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.kill
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import platform.posix.write
import kotlin.time.Duration.Companion.milliseconds

public actual class ShellClient actual constructor() :
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default),
    AutoCloseable {

    public actual suspend fun start(command: ShellProcessCommand): ProcessSession =
        withContext(PosixProcessIoDispatcher) {
            this@ShellClient.requireOpen()
            command.startPosixProcess(this@ShellClient)
        }

    public actual override fun close() {
        cancel()
    }
}

private val PosixProcessIoDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(64, "CodexLite.ProcessIO")

private fun ShellProcessCommand.startPosixProcess(parentScope: CoroutineScope): ProcessSession {
    if (command.isBlank()) {
        throw ProcessException("Process command must not be blank.")
    }
    val invocation = shell.invocation(
        command = command,
        workingDirectory = workingDirectory,
        login = login,
    )
    if (tty) return startPosixPtyProcess(invocation, parentScope)

    return withPosixPipe { stdinRead, stdinWrite ->
        withPosixPipe { outputRead, outputWrite ->
            stdinWrite.setNonBlocking()
            outputRead.setNonBlocking()
            memScoped {
                val pid = alloc<IntVar>()
                val processGroup = alloc<IntVar>()
                val result = codexlite_spawn_shell(
                    pid = pid.ptr,
                    process_group = processGroup.ptr,
                    stdin_read = stdinRead,
                    stdin_write = stdinWrite,
                    output_read = outputRead,
                    output_write = outputWrite,
                    shell = invocation.executable,
                    first_argument = invocation.argumentsBeforeCommand.getOrNull(0),
                    second_argument = invocation.argumentsBeforeCommand.getOrNull(1),
                    command = invocation.command,
                )
                if (result != 0) {
                    throw ProcessException("Failed to start process with ${invocation.executable}: error $result.")
                }
                PosixPipeTransfer(
                    value = PosixProcess(
                        pid = pid.value,
                        ownsProcessGroup = processGroup.value != 0,
                        stdinFd = stdinWrite,
                        outputFd = outputRead,
                        parentScope = parentScope,
                    ),
                    transferRead = true,
                )
            }
        }.let { session ->
            PosixPipeTransfer(
                value = session,
                transferWrite = true,
            )
        }
    }
}

private fun startPosixPtyProcess(
    invocation: ShellInvocation,
    parentScope: CoroutineScope,
): ProcessSession = memScoped {
    val pid = alloc<IntVar>()
    val masterFd = alloc<IntVar>()
    val result = codexlite_spawn_pty_shell(
        pid = pid.ptr,
        master_fd = masterFd.ptr,
        shell = invocation.executable,
        first_argument = invocation.argumentsBeforeCommand.getOrNull(0),
        second_argument = invocation.argumentsBeforeCommand.getOrNull(1),
        command = invocation.command,
    )
    if (result != 0) {
        throw ProcessException("Failed to start PTY with ${invocation.executable}: error $result.")
    }

    val descriptor = masterFd.value
    try {
        descriptor.setNonBlocking()
        PosixProcess(
            pid = pid.value,
            ownsProcessGroup = true,
            stdinFd = descriptor,
            outputFd = descriptor,
            tty = true,
            parentScope = parentScope,
        )
    } catch (failure: Throwable) {
        close(descriptor)
        throw failure
    }
}

private data class PosixPipeTransfer<T>(
    val value: T,
    val transferRead: Boolean = false,
    val transferWrite: Boolean = false,
)

private fun <T> withPosixPipe(block: (read: Int, write: Int) -> PosixPipeTransfer<T>): T = memScoped {
    val descriptors = allocArray<IntVar>(2)
    checkPlatformResult(pipe(descriptors), "create process pipe")
    val read = descriptors[0]
    val write = descriptors[1]
    try {
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

private class PosixProcess(
    private val pid: Int,
    private val ownsProcessGroup: Boolean,
    private val stdinFd: Int,
    private val outputFd: Int,
    private val tty: Boolean = false,
    parentScope: CoroutineScope,
) : ProcessSession {
    private val resourceMutex: Mutex = Mutex()
    private val stdinClosed: CompletableDeferred<Unit> = CompletableDeferred()
    private val outputClosed: CompletableDeferred<Unit> = CompletableDeferred()
    private val sessionJob: CompletableJob = SupervisorJob(parentScope.coroutineContext[Job])
    override val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    override val stdin: SendChannel<String>
        field = StdinChannel(scope, sessionJob)
    override val stdout: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val terminationRequested: CompletableDeferred<Unit> = CompletableDeferred()

    private val cancellationGuard: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            releaseAfterSessionEnd()
        }
    }

    private val stdinWriter: Job = scope.launch(PosixProcessIoDispatcher) {
        try {
            consumeStdin()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    init {
        scope.launch(PosixProcessIoDispatcher) {
            try {
                while (true) {
                    for (chunk in readAvailableOutput()) {
                        emitOutput(chunk)
                    }
                    val exitCode = exitCodeOrNull()
                    if (exitCode != null) {
                        for (chunk in readAvailableOutput()) {
                            emitOutput(chunk)
                        }
                        complete(exitCode)
                        return@launch
                    }
                    delay(10.milliseconds)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    override fun close() {
        if (exitCode.isCompleted || !terminationRequested.complete(Unit)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val cancellation: CancellationException = processCancellation()
            stdin.abort(cancellation)
            try {
                terminateProcessTree()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    private suspend fun emitOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        sessionJob.ensureActive()
        stdout.send(bytes)
    }

    private suspend fun complete(exitCode: Int) {
        stdout.close()
        stdout.flush()
        if (!this.exitCode.complete(exitCode)) return

        stdout.signalTerminal()
        stdin.close()
        scope.launch {
            stdinWriter.join()
            cancellationGuard.cancel()
            cancellationGuard.join()
            sessionJob.complete()
        }
    }

    private fun fail(failure: Throwable) {
        if (exitCode.completeExceptionally(failure)) {
            sessionJob.cancel(processCancellation(failure))
        }
    }

    private suspend fun consumeStdin() {
        while (true) {
            val next: PendingStdin = stdin.receiveCatching().getOrNull() ?: break
            if (next.written.isCompleted) continue

            try {
                writeStdin(next.text)
                stdin.succeed(next)
            } catch (failure: CancellationException) {
                stdin.fail(next, failure)
                throw failure
            } catch (failure: Throwable) {
                stdin.fail(next, failure)
                fail(failure)
                return
            }
        }

        try {
            closeStdin()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private suspend fun releaseAfterSessionEnd() {
        withContext(NonCancellable) {
            if (exitCode.isCompleted && !exitCode.isCancelled) {
                stdout.finish()
            } else {
                val cancellation: CancellationException = processCancellation()
                exitCode.completeExceptionally(cancellation)
                stdin.abort(cancellation)
                try {
                    terminateProcessTree()
                } catch (_: Throwable) {
                    // Resource release below is still required after a failed termination request.
                }
                stdout.abort(cancellation)
            }
            try {
                releaseResources()
            } catch (_: Throwable) {
                // The session is terminal; platform cleanup is best effort.
            }
        }
    }

    private suspend fun writeStdin(text: String): Unit = withContext(PosixProcessIoDispatcher) {
        resourceMutex.withLock {
            if (stdinClosed.isCompleted) {
                throw ProcessException("Process standard input is closed.")
            }
            val bytes = text.encodeToByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned { pinned ->
                    write(
                        stdinFd,
                        pinned.addressOf(offset),
                        (bytes.size - offset).toULong(),
                    )
                }
                val error = errno
                when {
                    count > 0 -> offset += count.toInt()
                    error == EAGAIN || error == EINTR -> delay(10.milliseconds)
                    else -> throw ProcessException("Failed to write to process standard input: errno $error.")
                }
            }
        }
    }

    private suspend fun closeStdin(): Unit = withContext(PosixProcessIoDispatcher) {
        resourceMutex.withLock {
            closeInputLocked()
        }
    }

    private suspend fun terminateProcessTree(): Unit = withContext(PosixProcessIoDispatcher) {
        if (!ownsProcessGroup || kill(-pid, SIGKILL) != 0) {
            kill(pid, SIGKILL)
        }
    }

    private suspend fun releaseResources(): Unit = withContext(PosixProcessIoDispatcher) {
        resourceMutex.withLock {
            closeInputLocked()
            closeOutputLocked()
        }
        reapAfterTermination()
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.")

    private fun closeInputLocked() {
        if (stdinClosed.complete(Unit) && !tty) {
            close(stdinFd)
        }
    }

    private fun closeOutputLocked() {
        if (outputClosed.complete(Unit)) {
            close(outputFd)
        }
    }

    private suspend fun readAvailableOutput(): List<ByteArray> = resourceMutex.withLock {
        val output = outputFd
        val chunks: MutableList<ByteArray> = mutableListOf()
        val bytes = ByteArray(8_192)
        while (true) {
            val count = bytes.usePinned { pinned ->
                read(output, pinned.addressOf(0), bytes.size.toULong())
            }
            val error = errno
            when {
                count > 0 -> chunks += bytes.copyOf(count.toInt())
                count == 0L -> break
                error == EAGAIN || error == EINTR -> break
                error == EIO && tty -> break
                else -> throw ProcessException("Failed to read process output: errno $error.")
            }
        }
        chunks
    }

    private fun exitCodeOrNull(): Int? {
        return memScoped {
            val status = alloc<IntVar>()
            when (waitpid(pid, status.ptr, WNOHANG)) {
                0 -> null
                pid -> exitCode(status.value)
                -1 -> when (errno) {
                    ECHILD -> null
                    EINTR -> null
                    else -> throw ProcessException("Failed to observe process exit: errno $errno.")
                }

                else -> throw ProcessException("Failed to observe process exit: errno $errno.")
            }
        }
    }

    /** Reaps only after the platform observer has finished, or has been cancelled. */
    private suspend fun reapAfterTermination() {
        while (true) {
            val result = memScoped {
                val status = alloc<IntVar>()
                val result = waitpid(pid, status.ptr, WNOHANG)
                result to errno
            }
            when (result.first) {
                pid -> return
                -1 -> when (result.second) {
                    EINTR,
                    ECHILD,
                    -> return

                    else -> return
                }

                0 -> delay(10.milliseconds)
                else -> return
            }
        }
    }

}

private fun Int.setNonBlocking() {
    val flags = fcntl(this, F_GETFL)
    if (flags == -1) {
        throw ProcessException("Failed to read process file descriptor flags: errno $errno.")
    }
    checkPlatformResult(fcntl(this, F_SETFL, flags or O_NONBLOCK), "set process file descriptor non-blocking")
}

private fun checkPlatformResult(result: Int, operation: String) {
    if (result == -1) {
        throw ProcessException("Failed to $operation: errno $errno.")
    }
}

private fun exitCode(status: Int): Int {
    val signal = status and 0x7f
    return if (signal == 0) (status ushr 8) and 0xff else 128 + signal
}
