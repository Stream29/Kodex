@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.shellclient

import io.github.stream29.kodex.utils.shellclient.conpty.kodex_close_windows_pseudo_console
import io.github.stream29.kodex.utils.shellclient.conpty.kodex_spawn_windows_pty
import io.github.stream29.kodex.utils.processclient.ProcessClient
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import platform.windows.AssignProcessToJobObject
import platform.windows.CloseHandle
import platform.windows.CreateJobObjectW
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.PeekNamedPipe
import platform.windows.ReadFile
import platform.windows.ResumeThread
import platform.windows.TerminateProcess
import platform.windows.TerminateJobObject
import platform.windows.WAIT_OBJECT_0
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile
import kotlin.time.Duration.Companion.milliseconds

public actual class ShellClient internal actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    AutoCloseable {
    private val processClient = scope.ProcessClient()

    public actual suspend fun start(command: ShellProcessCommand): ProcessSession =
        withContext(WindowsProcessIoDispatcher) {
            this@ShellClient.requireOpen()
            command.startWindowsProcess(processClient, this@ShellClient)
        }

    public actual override fun close() {
        cancel()
    }
}

private val WindowsProcessIoDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(Int.MAX_VALUE, "Kodex.ProcessIO")

private suspend fun ShellProcessCommand.startWindowsProcess(
    processClient: ProcessClient,
    parentScope: CoroutineScope,
): ProcessSession {
    if (command.isBlank()) {
        throw ProcessException("Process command must not be blank.")
    }
    if (!tty) {
        return parentScope.startPipeProcess(
            client = processClient,
            invocation = shell.invocation(command, login),
            command = this,
        )
    }
    return startWindowsPtyProcess(parentScope)
}

private fun ShellProcessCommand.startWindowsPtyProcess(parentScope: CoroutineScope): ProcessSession = memScoped {
    val process = alloc<COpaquePointerVar>()
    val thread = alloc<COpaquePointerVar>()
    val pseudoConsole = alloc<COpaquePointerVar>()
    val stdin = alloc<COpaquePointerVar>()
    val output = alloc<COpaquePointerVar>()
    val result = kodex_spawn_windows_pty(
        process_out = process.ptr,
        thread_out = thread.ptr,
        pseudo_console_out = pseudoConsole.ptr,
        stdin_write_out = stdin.ptr,
        output_read_out = output.ptr,
        command_line = windowsStringBuffer(windowsCommandLine()),
        working_directory = windowsStringBuffer(workingDirectory.windowsPath()),
        environment = windowsEnvironmentBlock(environment),
        columns = DefaultPtyColumns.toShort(),
        rows = DefaultPtyRows.toShort(),
    )
    if (result != 0) {
        throw ProcessException("Failed to start Windows pseudoterminal: error $result.")
    }

    val processHandle = requireNotNull(process.value)
    val threadHandle = requireNotNull(thread.value)
    val pseudoConsoleHandle = requireNotNull(pseudoConsole.value)
    val stdinHandle = requireNotNull(stdin.value)
    val outputHandle = requireNotNull(output.value)
    val job = try {
        // Attach the root process before it can create descendants.
        createWindowsProcessJob(processHandle)
    } catch (failure: Throwable) {
        TerminateProcess(processHandle, 1u)
        closeWindowsPseudoConsole(pseudoConsoleHandle)
        closeWindowsHandle(stdinHandle)
        closeWindowsHandle(outputHandle)
        closeWindowsHandle(processHandle)
        closeWindowsHandle(threadHandle)
        throw failure
    }
    try {
        if (ResumeThread(threadHandle) == UInt.MAX_VALUE) {
            throw ProcessException("Failed to resume process: error ${GetLastError()}.")
        }
        WindowsProcess(
            processHandle = processHandle,
            jobHandle = job,
            stdinHandle = stdinHandle,
            outputHandle = outputHandle,
            errorHandle = null,
            pseudoConsole = pseudoConsoleHandle,
            tty = true,
            parentScope = parentScope,
        )
    } catch (failure: Throwable) {
        terminateWindowsProcessTree(job, processHandle)
        closeWindowsHandle(job)
        closeWindowsPseudoConsole(pseudoConsoleHandle)
        closeWindowsHandle(stdinHandle)
        closeWindowsHandle(outputHandle)
        closeWindowsHandle(processHandle)
        throw failure
    } finally {
        closeWindowsHandle(threadHandle)
    }
}

/**
 * @property jobHandle `null` when Windows could not create a Job Object. In that
 * case, cancellation falls back to terminating the root process handle.
 */
private class WindowsProcess(
    private val processHandle: CPointer<out CPointed>,
    private val jobHandle: CPointer<out CPointed>?,
    private val stdinHandle: CPointer<out CPointed>,
    private val outputHandle: CPointer<out CPointed>,
    private val errorHandle: CPointer<out CPointed>?,
    private val pseudoConsole: COpaquePointer? = null,
    private val tty: Boolean = false,
    parentScope: CoroutineScope,
) : ProcessSession {
    private val resourceMutex: Mutex = Mutex()
    private val stdinClosed: CompletableDeferred<Unit> = CompletableDeferred()
    private val stdinHandleClosed: CompletableDeferred<Unit> = CompletableDeferred()
    private val sessionJob: CompletableJob = SupervisorJob(parentScope.coroutineContext[Job])
    override val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    override val stdin: SendChannel<String>
        field = StdinChannel(scope, sessionJob)
    override val stdout: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    override val standardOutput: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    override val standardError: StdoutBuffer
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

    private val stdinWriter: Job = scope.launch(WindowsProcessIoDispatcher) {
        try {
            consumeStdin()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    init {
        scope.launch(WindowsProcessIoDispatcher) {
            try {
                while (true) {
                    for (chunk in readAvailableOutput(outputHandle)) {
                        emitOutput(chunk, standardOutput)
                    }
                    errorHandle?.let { handle ->
                        for (chunk in readAvailableOutput(handle)) {
                            emitOutput(chunk, standardError)
                        }
                    }
                    val exitCode = exitCodeOrNull()
                    if (exitCode != null) {
                        for (chunk in readAvailableOutput(outputHandle)) {
                            emitOutput(chunk, standardOutput)
                        }
                        errorHandle?.let { handle ->
                            for (chunk in readAvailableOutput(handle)) {
                                emitOutput(chunk, standardError)
                            }
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

    private suspend fun emitOutput(bytes: ByteArray, stream: MutableStdoutBuffer) {
        if (bytes.isEmpty()) return
        sessionJob.ensureActive()
        stdout.send(bytes)
        stream.send(bytes)
    }

    private suspend fun complete(exitCode: Int) {
        stdout.close()
        standardOutput.close()
        standardError.close()
        stdout.flush()
        standardOutput.flush()
        standardError.flush()
        stdin.close()
        if (!this.exitCode.complete(exitCode)) return

        stdout.signalTerminal()
        standardOutput.signalTerminal()
        standardError.signalTerminal()

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
                standardOutput.finish()
                standardError.finish()
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
                standardOutput.abort(cancellation)
                standardError.abort(cancellation)
            }
            try {
                releaseResources()
            } catch (_: Throwable) {
                // The session is terminal; platform cleanup is best effort.
            }
        }
    }

    private suspend fun writeStdin(text: String): Unit =
        withContext(WindowsProcessIoDispatcher) {
            resourceMutex.withLock {
                if (stdinClosed.isCompleted) {
                    throw ProcessException("Process standard input is closed.")
                }
                val bytes = if (tty) text.normalizedForWindowsPty().encodeToByteArray() else text.encodeToByteArray()
                var offset = 0
                while (offset < bytes.size) {
                    val byteCount = memScoped {
                        val written = alloc<UIntVar>()
                        val success = bytes.usePinned { pinned ->
                            WriteFile(
                                stdinHandle,
                                pinned.addressOf(offset),
                                (bytes.size - offset).toUInt(),
                                written.ptr,
                                null,
                            )
                        }
                        checkWindowsSuccess(success, "write to process standard input")
                        written.value.toInt()
                    }
                    if (byteCount == 0) {
                        throw ProcessException("Process standard input accepted no bytes.")
                    }
                    offset += byteCount
                }
            }
        }

    private suspend fun closeStdin(): Unit = withContext(WindowsProcessIoDispatcher) {
        resourceMutex.withLock {
            closeInputLocked()
        }
    }

    private suspend fun terminateProcessTree(): Unit = withContext(WindowsProcessIoDispatcher) {
        // A synchronous WriteFile can hold resourceMutex until the child exits.
        // Termination must therefore remain able to close the other end first.
        terminateWindowsProcessTree(jobHandle, processHandle)
    }

    private suspend fun releaseResources(): Unit = withContext(WindowsProcessIoDispatcher) {
        resourceMutex.withLock {
            closeInputLocked(closeHandle = true)
            closeWindowsPseudoConsole(pseudoConsole)
            closeWindowsHandle(outputHandle)
            closeWindowsHandle(errorHandle)
            closeWindowsHandle(processHandle)
            closeWindowsHandle(jobHandle)
        }
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.")

    private fun closeInputLocked(closeHandle: Boolean = !tty) {
        stdinClosed.complete(Unit)
        if (closeHandle && stdinHandleClosed.complete(Unit)) {
            closeWindowsHandle(stdinHandle)
        }
    }

    private suspend fun readAvailableOutput(
        output: CPointer<out CPointed>,
    ): List<ByteArray> = resourceMutex.withLock {
        val chunks: MutableList<ByteArray> = mutableListOf()
        while (true) {
            val available = availableOutputByteCount(output)
            if (available == 0) break
            val bytes = ByteArray(minOf(available, 8_192))
            val count = memScoped {
                val read = alloc<UIntVar>()
                val success = bytes.usePinned { pinned ->
                    ReadFile(output, pinned.addressOf(0), bytes.size.toUInt(), read.ptr, null)
                }
                if (success == 0 && GetLastError() == ERROR_BROKEN_PIPE.toUInt()) return@memScoped 0
                checkWindowsSuccess(success, "read process output")
                read.value.toInt()
            }
            if (count == 0) break
            chunks += bytes.copyOf(count)
        }
        chunks
    }

    private fun availableOutputByteCount(output: CPointer<out CPointed>): Int = memScoped {
        val available = alloc<UIntVar>()
        val success = PeekNamedPipe(output, null, 0u, null, available.ptr, null)
        if (success == 0 && GetLastError() == ERROR_BROKEN_PIPE.toUInt()) return@memScoped 0
        checkWindowsSuccess(success, "inspect process output")
        available.value.toInt()
    }

    private suspend fun exitCodeOrNull(): Int? = resourceMutex.withLock {
        val process = processHandle
        when (WaitForSingleObject(process, 0u)) {
            WAIT_TIMEOUT.toUInt() -> null
            WAIT_OBJECT_0 -> memScoped {
                val exitCode = alloc<UIntVar>()
                checkWindowsSuccess(GetExitCodeProcess(process, exitCode.ptr), "read process exit code")
                exitCode.value.toInt()
            }

            else -> throw ProcessException("Failed to observe process exit: error ${GetLastError()}.")
        }
    }
}

private fun ShellProcessCommand.windowsCommandLine(): String {
    val invocation = shell.invocation(command, login)
    return (listOf(invocation.executable) + invocation.argumentsBeforeCommand + invocation.command)
        .joinToString(" ") { argument -> argument.quoteWindowsArgument() }
}

private fun String.quoteWindowsArgument(): String =
    if (isNotEmpty() && none { it.isWhitespace() || it == '"' }) {
        this
    } else {
        buildString(length + 2) {
            append('"')
            var backslashCount = 0
            this@quoteWindowsArgument.forEach { character ->
                when (character) {
                    '\\' -> backslashCount += 1
                    '"' -> {
                        repeat(backslashCount * 2 + 1) { append('\\') }
                        append('"')
                        backslashCount = 0
                    }

                    else -> {
                        repeat(backslashCount) { append('\\') }
                        append(character)
                        backslashCount = 0
                    }
                }
            }
            repeat(backslashCount * 2) { append('\\') }
            append('"')
        }
    }

private fun kotlinx.cinterop.MemScope.windowsStringBuffer(value: String): CPointer<UShortVar> {
    val buffer = allocArray<UShortVar>(value.length + 1)
    value.forEachIndexed { index, character -> buffer[index] = character.code.toUShort() }
    buffer[value.length] = 0u
    return buffer
}

private fun kotlinx.cinterop.MemScope.windowsEnvironmentBlock(
    overrides: Map<String, String>,
): CPointer<UShortVar>? {
    if (overrides.isEmpty()) return null
    val entriesByName = currentWindowsEnvironment()
        .associateByTo(linkedMapOf()) { entry -> entry.windowsEnvironmentName().lowercase() }
    overrides.forEach { (name, value) ->
        entriesByName[name.lowercase()] = "$name=$value"
    }
    val entries = entriesByName.values.sortedWith(
        Comparator { left, right ->
            left.windowsEnvironmentName().compareTo(right.windowsEnvironmentName(), ignoreCase = true)
        },
    )
    val buffer = allocArray<UShortVar>(entries.sumOf { entry -> entry.length + 1 } + 1)
    var offset = 0
    entries.forEach { entry ->
        entry.forEach { character -> buffer[offset++] = character.code.toUShort() }
        buffer[offset++] = 0u
    }
    buffer[offset] = 0u
    return buffer
}

private fun currentWindowsEnvironment(): List<String> {
    val block = GetEnvironmentStringsW()
        ?: throw ProcessException("Failed to read process environment: error ${GetLastError()}.")
    return try {
        buildList {
            var cursor = block
            while (cursor[0] != 0.toUShort()) {
                val entry = cursor.toKStringFromUtf16()
                add(entry)
                cursor = (cursor + entry.length + 1)!!
            }
        }
    } finally {
        FreeEnvironmentStringsW(block)
    }
}

private fun String.windowsEnvironmentName(): String {
    val separator = indexOf('=', startIndex = if (startsWith('=')) 1 else 0)
    return if (separator == -1) this else substring(0, separator)
}

private fun checkWindowsSuccess(success: Int, operation: String) {
    if (success == 0) {
        throw ProcessException("Failed to $operation: error ${GetLastError()}.")
    }
}

private fun closeWindowsHandle(handle: CPointer<out CPointed>?) {
    if (handle != null) CloseHandle(handle)
}

private fun closeWindowsPseudoConsole(pseudoConsole: COpaquePointer?) {
    pseudoConsole?.let(::kodex_close_windows_pseudo_console)
}

private fun terminateWindowsProcessTree(
    jobHandle: CPointer<out CPointed>?,
    processHandle: CPointer<out CPointed>,
) {
    jobHandle?.let { job -> TerminateJobObject(job, 1u) }
        ?: TerminateProcess(processHandle, 1u)
}

private fun createWindowsProcessJob(process: CPointer<out CPointed>): CPointer<out CPointed>? {
    val job = CreateJobObjectW(null, null) ?: return null
    return if (AssignProcessToJobObject(job, process) != 0) {
        job
    } else {
        closeWindowsHandle(job)
        null
    }
}

private fun String.normalizedForWindowsPty(): String =
    replace("\r\n", "\r").replace("\n", "\r").replace("\b", "\u007f")
