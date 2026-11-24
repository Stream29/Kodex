@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    io.github.stream29.kodex.utils.processclient.NativeProcessAdapterApi::class,
)

package io.github.stream29.kodex.utils.shellclient

import io.github.stream29.kodex.utils.processclient.NativeProcessExit
import io.github.stream29.kodex.utils.processclient.NativeProcessStream
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.adoptNativeProcessStream
import io.github.stream29.kodex.utils.processclient.watchNativeProcessExit
import io.github.stream29.kodex.utils.shellclient.conpty.kodex_close_windows_pseudo_console
import io.github.stream29.kodex.utils.shellclient.conpty.kodex_create_pty_job
import io.github.stream29.kodex.utils.shellclient.conpty.kodex_spawn_windows_pty
import io.github.stream29.kodex.utils.shellclient.conpty.kodex_terminate_pty_tree
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import platform.windows.CloseHandle
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetLastError
import platform.windows.ResumeThread
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.seconds

internal actual class PlatformShellClient actual constructor(
    scope: CoroutineScope,
) : CoroutineScope by scope, ShellClient {
    private val processClient = scope.ProcessClient()

    actual override suspend fun start(command: ShellProcessCommand): ProcessSession =
        acquireShellSession(WindowsPtyStart) {
            requireOpen()
            if (command.command.isBlank()) throw ProcessException("Process command must not be blank.")
            if (command.tty) {
                command.startWindowsPtyProcess(this@PlatformShellClient)
            } else {
                startPipeProcess(processClient, command.shell.invocation(command.command, command.login), command)
            }
        }

    actual override fun close() {
        cancel()
    }
}

private val WindowsPtyStart = Dispatchers.IO.limitedParallelism(4, "Kodex.WindowsPtyStart")
private val WindowsPtyClose = Dispatchers.IO.limitedParallelism(4, "Kodex.WindowsPtyClose")

private suspend fun ShellProcessCommand.startWindowsPtyProcess(owner: CoroutineScope): ProcessSession {
    val native = memScoped {
        val process = alloc<COpaquePointerVar>()
        val thread = alloc<COpaquePointerVar>()
        val console = alloc<COpaquePointerVar>()
        val stdin = alloc<COpaquePointerVar>()
        val output = alloc<COpaquePointerVar>()
        val result = kodex_spawn_windows_pty(
            process.ptr, thread.ptr, console.ptr, stdin.ptr, output.ptr,
            windowsStringBuffer(windowsCommandLine()), windowsStringBuffer(workingDirectory.windowsPath()),
            windowsEnvironmentBlock(environment), DefaultPtyColumns.toShort(), DefaultPtyRows.toShort(),
        )
        if (result != 0) throw ProcessException("Failed to start Windows pseudoterminal: error $result.")
        SpawnedPty(
            requireNotNull(process.value), requireNotNull(thread.value), requireNotNull(console.value),
            requireNotNull(stdin.value), requireNotNull(output.value),
        )
    }
    var thread: COpaquePointer? = native.thread
    var job: COpaquePointer? = null
    var watch: NativeProcessExit? = null
    var input: NativeProcessStream? = null
    var output: NativeProcessStream? = null
    try {
        val jobHandle = memScoped {
            val out = alloc<COpaquePointerVar>()
            val error = kodex_create_pty_job(native.process, out.ptr)
            if (error != 0u) throw ProcessException("Failed to assign pseudoterminal Job Object: error $error.")
            requireNotNull(out.value)
        }
        job = jobHandle
        val stdin = adoptNativeProcessStream(native.stdin.rawValue.toLong(), terminal = true)
        input = stdin
        val stdout = adoptNativeProcessStream(native.output.rawValue.toLong(), terminal = true)
        output = stdout
        val exitWatch = watchNativeProcessExit(native.process.rawValue.toLong(), jobHandle.rawValue.toLong())
        watch = exitWatch
        if (ResumeThread(native.thread) == UInt.MAX_VALUE) {
            throw ProcessException("Failed to resume process: error ${GetLastError()}.")
        }
        thread = null
        closeHandle(native.thread)
        return WindowsPtySession(native.process, jobHandle, native.console, exitWatch, stdin, stdout, owner)
    } catch (primary: Throwable) {
        withContext(NonCancellable) {
            val cleanup = PtyCleanup(primary)
            input?.requestClose()
            output?.requestClose()
            val terminated = cleanup.attempt {
                val watcher = watch
                if (watcher != null) watcher.terminate()
                else {
                    val error = kodex_terminate_pty_tree(native.process, job)
                    if (error != 0u) throw ProcessException("Failed to terminate pseudoterminal process tree: error $error.")
                }
            }
            cleanup.attempt { input?.close() ?: closeHandle(native.stdin) }
            cleanup.attempt { output?.close() ?: closeHandle(native.output) }
            cleanup.attempt { closeWindowsConsole(native.console) }
            if (terminated) cleanup.attempt { watch?.awaitTerminated() }
            if (cleanup.attempt { watch?.close() }) {
                cleanup.attempt { closeHandle(job) }
                cleanup.attempt { closeHandle(native.process) }
            }
            cleanup.attempt { closeHandle(thread) }
        }
        throw primary
    }
}

private class SpawnedPty(
    val process: COpaquePointer,
    val thread: COpaquePointer,
    val console: COpaquePointer,
    val stdin: COpaquePointer,
    val output: COpaquePointer,
)

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class WindowsPtySession(
    private val process: COpaquePointer,
    private val job: COpaquePointer,
    private val console: COpaquePointer,
    private val watch: NativeProcessExit,
    private val input: NativeProcessStream,
    private val output: NativeProcessStream,
    owner: CoroutineScope,
) : ProcessSession {
    private val sessionJob = SupervisorJob(owner.coroutineContext[Job])
    override val scope = CoroutineScope(owner.coroutineContext + sessionJob)
    override val stdin: SendChannel<String>
        field = StdinChannel(scope, sessionJob)
    override val stdout: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    override val standardOutput: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    override val standardError: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    private val buffers: List<MutableStdoutBuffer> = listOf(stdout, standardOutput, standardError)
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val explicitClose = AtomicBoolean(false)
    private val abortingPipes = AtomicBoolean(false)
    private val stoppingInput = AtomicBoolean(false)
    private val primaryFailure = AtomicReference<Throwable?>(null)
    private var consoleClosed = false // Only the lifecycle coroutine calls closeConsole.

    // Still runs when the lifecycle coroutine is inside a non-cancellable,
    // blocking ClosePseudoConsole on Windows builds before 24H2.
    private val cancellationGuard = scope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
        try {
            awaitCancellation()
        } finally {
            if (sessionJob.isCancelled) {
                abortPipes()
                runCatching { watch.terminate() }.exceptionOrNull()?.let {
                    primaryFailure.compareAndSet(null, it)
                }
            }
        }
    }

    private val writer = scope.launch(Dispatchers.Default) {
        try {
            consumeInput()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IOException) {
            // An individual stdin send can fail as the child detaches. It
            // must not replace the process's independent native exit status.
            if (!stoppingInput.load()) stdin.abort(failure)
        } catch (failure: Throwable) {
            if (!stoppingInput.load()) fail(failure)
        }
    }

    private val reader = scope.launch(Dispatchers.Default) {
        try {
            val buffer = Buffer()
            while (true) {
                val size = output.readAtMostTo(buffer, 8_192L)
                if (size < 0L) break
                val bytes = buffer.readByteArray(size.toInt())
                stdout.send(bytes)
                standardOutput.send(bytes)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IOException) {
            if (!abortingPipes.load()) fail(failure)
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    init {
        scope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            var result: Int? = null
            var cancellation: CancellationException? = null
            try {
                val code = watch.await()
                stoppingInput.store(true)
                stdin.close()
                input.requestClose()
                writer.cancelAndJoin()
                // Pre-24H2 can block until pending output is read; 24H2+
                // returns without waiting for it. In both cases await pipe EOF.
                closeConsole()
                reader.join()
                ensureActive()
                buffers.forEach { it.flush() }
                buffers.forEach { it.signalTerminal() }
                result = code
            } catch (failure: CancellationException) {
                cancellation = failure
            } catch (failure: Throwable) {
                primaryFailure.compareAndSet(null, failure)
            } finally {
                withContext(NonCancellable) {
                    val cleanup = PtyCleanup(primaryFailure.load() ?: cancellation)
                    val awaitTermination = if (result == null) {
                        abortPipes()
                        val terminated = cleanup.attempt { watch.terminate() }
                        val cause = cleanup.failure ?: CancellationException("Process session is closed.")
                        stdin.abort(cause)
                        buffers.forEach { it.abort(cause) }
                        terminated
                    } else {
                        buffers.forEach { it.finish() }
                        false
                    }
                    cleanup.attempt { input.close() }
                    cleanup.attempt { output.close() }
                    cleanup.attempt { closeConsole() }
                    if (awaitTermination) cleanup.attempt { watch.awaitTerminated() }
                    if (cleanup.attempt { watch.close() }) {
                        cleanup.attempt { closeHandle(job) }
                        cleanup.attempt { closeHandle(process) }
                    }
                    cancellationGuard.cancelAndJoin()
                    val failure = cleanup.failure
                    if (failure != null) exitCode.completeExceptionally(failure)
                    else exitCode.complete(requireNotNull(result))
                    sessionJob.complete()
                }
            }
        }
    }

    override fun close() {
        if (exitCode.isCompleted || !explicitClose.compareAndSet(false, true)) return
        abortPipes()
        stdin.abort(CancellationException("Process session is closed."))
        scope.launch(Dispatchers.Default) {
            try {
                watch.terminate()
                watch.awaitTerminated()
            } catch (failure: Throwable) {
                if (failure !is CancellationException) fail(failure)
            }
        }
    }

    private fun abortPipes() {
        abortingPipes.store(true)
        stoppingInput.store(true)
        input.requestClose()
        output.requestClose()
    }

    private fun fail(failure: Throwable) {
        if (primaryFailure.compareAndSet(null, failure)) {
            sessionJob.cancel(CancellationException("Windows pseudoterminal failed."))
        }
    }

    private suspend fun consumeInput() {
        while (true) {
            val next = stdin.receiveCatching().getOrNull() ?: return
            if (next.written.isCompleted) continue
            try {
                val text = next.text.replace("\r\n", "\r").replace("\n", "\r").replace("\b", "\u007f")
                val buffer = Buffer().apply { write(text.encodeToByteArray()) }
                input.write(buffer, buffer.size)
                stdin.succeed(next)
            } catch (failure: Throwable) {
                stdin.fail(next, failure)
                throw failure
            }
        }
    }

    private suspend fun closeConsole() {
        if (consoleClosed) return
        consoleClosed = true
        closeWindowsConsole(console)
    }
}

private suspend fun NativeProcessExit.awaitTerminated(): Int =
    withTimeoutOrNull(7.seconds) { await() }
        ?: throw ProcessException("Timed out waiting for terminated pseudoterminal process to exit.")

private suspend fun closeWindowsConsole(console: COpaquePointer): Unit =
    withContext(NonCancellable + WindowsPtyClose) {
        val error = kodex_close_windows_pseudo_console(console)
        if (error != 0u) throw ProcessException("Failed to close pseudoterminal pipe handles: error $error.")
    }

private class PtyCleanup(var failure: Throwable?) {
    suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cleanup: Throwable) {
        val primary = failure
        if (primary == null) failure = cleanup else if (cleanup !== primary) primary.addSuppressed(cleanup)
        false
    }
}

private fun closeHandle(handle: COpaquePointer?) {
    if (handle != null && CloseHandle(handle) == 0) {
        throw ProcessException("Failed to close pseudoterminal handle: error ${GetLastError()}.")
    }
}

private fun ShellProcessCommand.windowsCommandLine(): String {
    val invocation = shell.invocation(command, login)
    return (listOf(invocation.executable) + invocation.argumentsBeforeCommand + invocation.command)
        .joinToString(" ") { it.quoteWindowsArgument() }
}

private fun String.quoteWindowsArgument(): String =
    if (isNotEmpty() && none { it.isWhitespace() || it == '"' }) this else buildString(length + 2) {
        append('"')
        var backslashes = 0
        for (character in this@quoteWindowsArgument) {
            if (character == '\\') {
                backslashes++
                continue
            }
            repeat(if (character == '"') backslashes * 2 + 1 else backslashes) { append('\\') }
            backslashes = 0
            append(character)
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }

private fun MemScope.windowsStringBuffer(value: String): CPointer<UShortVar> {
    val buffer = allocArray<UShortVar>(value.length + 1)
    value.forEachIndexed { index, character -> buffer[index] = character.code.toUShort() }
    buffer[value.length] = 0u
    return buffer
}

private fun MemScope.windowsEnvironmentBlock(overrides: Map<String, String>): CPointer<UShortVar>? {
    if (overrides.isEmpty()) return null
    val entriesByName = currentWindowsEnvironment()
        .associateByTo(linkedMapOf()) { it.windowsEnvironmentName().lowercase() }
    overrides.forEach { (name, value) -> entriesByName[name.lowercase()] = "$name=$value" }
    val entries = entriesByName.values.sortedWith(
        Comparator { left, right -> left.windowsEnvironmentName().compareTo(right.windowsEnvironmentName(), ignoreCase = true) },
    )
    val buffer = allocArray<UShortVar>(entries.sumOf { it.length + 1 } + 1)
    var offset = 0
    for (entry in entries) {
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

