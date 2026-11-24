@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, NativeProcessAdapterApi::class)

package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_create_process_job
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_create_process_pipe
import io.github.stream29.kodex.utils.processclient.winspawn.kodex_spawn_windows_process
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import platform.windows.CloseHandle
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetLastError
import platform.windows.ResumeThread
import kotlin.time.Duration.Companion.seconds

internal actual class PlatformProcessClient actual constructor(
    scope: CoroutineScope,
) : CoroutineScope by scope, ProcessClient {
    actual override suspend fun start(command: ProcessCommand): ProcessSession =
        acquireProcessSession(WindowsProcessStart) {
            requireOpen()
            command.startWindowsProcess(this@PlatformProcessClient)
        }

    actual override fun close() {
        cancel()
    }
}

private val WindowsProcessStart = Dispatchers.IO.limitedParallelism(4, "Kodex.WindowsProcessStart")

private suspend fun ProcessCommand.startWindowsProcess(owner: CoroutineScope): ProcessSession {
    val pipes = mutableListOf<ProcessPipe>()
    val streams = mutableListOf<NativeProcessStream>()
    var process: COpaquePointer? = null
    var thread: COpaquePointer? = null
    var job: COpaquePointer? = null
    var watch: NativeProcessExit? = null
    try {
        fun pipe(parentReads: Boolean): ProcessPipe = ProcessPipe(parentReads).also(pipes::add)
        fun adopt(pipe: ProcessPipe): NativeProcessStream = pipe.adopt().also(streams::add)
        val input = pipe(parentReads = false)
        val output = pipe(parentReads = true)
        val error = pipe(parentReads = true)
        val stdin = adopt(input)
        val stdout = adopt(output)
        val stderr = adopt(error)

        val (processHandle, threadHandle) = memScoped {
            val processOut = alloc<COpaquePointerVar>()
            val threadOut = alloc<COpaquePointerVar>()
            val result = kodex_spawn_windows_process(
                processOut.ptr, threadOut.ptr, windowsStringBuffer(windowsCommandLine()),
                windowsStringBuffer(workingDirectory.windowsPath()), windowsEnvironmentBlock(environment),
                input.child, output.child, error.child,
            )
            if (result != 0) throw ProcessException("Failed to start process: error $result.")
            requireNotNull(processOut.value) to requireNotNull(threadOut.value)
        }
        process = processHandle
        thread = threadHandle
        pipes.forEach { it.closeChild() }
        val jobHandle = memScoped {
            val out = alloc<COpaquePointerVar>()
            val result = kodex_create_process_job(processHandle, out.ptr)
            if (result != 0u) throw ProcessException("Failed to assign process Job Object: error $result.")
            requireNotNull(out.value)
        }
        job = jobHandle
        val exitWatch = watchNativeProcessExit(processHandle.rawValue.toLong(), jobHandle.rawValue.toLong())
        watch = exitWatch
        if (ResumeThread(threadHandle) == UInt.MAX_VALUE) {
            throw ProcessException("Failed to resume process: error ${GetLastError()}.")
        }
        thread = null
        closeHandle(threadHandle)
        return WindowsProcessSession(processHandle, jobHandle, exitWatch, stdin, stdout, stderr, owner)
    } catch (primary: Throwable) {
        withContext(NonCancellable) {
            val cleanup = Cleanup(primary)
            streams.forEach { it.requestClose() }
            val terminated = cleanup.attempt {
                val watcher = watch
                if (watcher != null) watcher.terminate()
                else process?.let { terminateWindowsProcessTree(it, job) }
            }
            for (pipe in pipes) {
                cleanup.attempt { pipe.closeChild() }
                cleanup.attempt { pipe.closeParent() }
            }
            for (stream in streams) cleanup.attempt { stream.close() }
            if (terminated) cleanup.attempt { watch?.awaitTerminated() }
            if (cleanup.attempt { watch?.close() }) {
                cleanup.attempt { closeHandle(job) }
                cleanup.attempt { closeHandle(process) }
            }
            cleanup.attempt { closeHandle(thread) }
        }
        throw primary
    }
}

private class ProcessPipe(parentReads: Boolean) {
    private var parent: COpaquePointer?
    var child: COpaquePointer?
        private set

    init {
        val (parentHandle, childHandle) = memScoped {
            val parentOut = alloc<COpaquePointerVar>()
            val childOut = alloc<COpaquePointerVar>()
            val error = kodex_create_process_pipe(parentOut.ptr, childOut.ptr, if (parentReads) 1 else 0)
            if (error != 0u) throw ProcessException("Failed to create process pipe: error $error.")
            requireNotNull(parentOut.value) to requireNotNull(childOut.value)
        }
        parent = parentHandle
        child = childHandle
    }

    fun adopt(): NativeProcessStream =
        adoptNativeProcessStream(requireNotNull(parent).rawValue.toLong(), terminal = false).also { parent = null }

    fun closeParent() {
        val handle = parent
        parent = null
        closeHandle(handle)
    }

    fun closeChild() {
        val handle = child
        child = null
        closeHandle(handle)
    }
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class WindowsProcessSession(
    private val process: COpaquePointer,
    private val job: COpaquePointer,
    private val watch: NativeProcessExit,
    private val input: NativeProcessStream,
    private val output: NativeProcessStream,
    private val error: NativeProcessStream,
    owner: CoroutineScope,
) : ProcessSession {
    override val stdin: CoroutineRawSink = input
    override val stdout: CoroutineRawSource = output
    override val stderr: CoroutineRawSource = error
    private val streams = listOf(input, output, error)
    private val exit = CompletableDeferred<Int>()
    override val exitCode: Deferred<Int> = exit
    private val released = CompletableDeferred<Result<Unit>>()

    // Keep the raw streams (and the job, for descendants) until session close,
    // but the root's exit status never waits for a pipe reader.
    private val lifecycle = owner.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
        var primary: Throwable? = null
        try {
            exit.complete(watch.await())
            awaitCancellation()
        } catch (_: CancellationException) {
            // Cancellation requests termination; publish its native status below.
        } catch (failure: Throwable) {
            primary = failure
            exit.completeExceptionally(failure)
        } finally {
            withContext(NonCancellable) {
                val cleanup = Cleanup(primary)
                streams.forEach { it.requestClose() }
                val terminated = cleanup.attempt { watch.terminate() }
                if (!exit.isCompleted) {
                    if (terminated) cleanup.attempt { exit.complete(watch.awaitTerminated()) }
                    cleanup.failure?.let { exit.completeExceptionally(it) }
                }
                if (cleanup.attempt { watch.close() }) {
                    cleanup.attempt { closeHandle(job) }
                    cleanup.attempt { closeHandle(process) }
                }
                for (stream in streams) cleanup.attempt { stream.close() }
                released.complete(cleanup.failure?.let { Result.failure(it) } ?: Result.success(Unit))
            }
        }
    }

    override fun close() {
        streams.forEach { it.requestClose() }
        lifecycle.cancel()
    }

    override suspend fun closeAndJoin(): Unit = withContext(NonCancellable) {
        close()
        lifecycle.join()
        released.await().getOrThrow()
    }
}

private suspend fun NativeProcessExit.awaitTerminated(): Int =
    withTimeoutOrNull(7.seconds) { await() }
        ?: throw ProcessException("Timed out waiting for terminated process to exit.")

private class Cleanup(var failure: Throwable?) {
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
        throw ProcessException("Failed to close process handle: error ${GetLastError()}.")
    }
}

private fun ProcessCommand.windowsCommandLine(): String =
    (listOf(executable) + arguments).joinToString(" ") { it.quoteWindowsArgument() }

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

private fun MemScope.windowsEnvironmentBlock(overrides: Map<String, String>): CPointer<UShortVar> {
    val entriesByName = currentWindowsEnvironment()
        .associateByTo(linkedMapOf()) { it.windowsEnvironmentName().lowercase() }
    overrides.forEach { (name, value) -> entriesByName[name.lowercase()] = "$name=$value" }
    val entries = entriesByName.values.sortedWith(
        Comparator { left, right -> left.windowsEnvironmentName().compareTo(right.windowsEnvironmentName(), ignoreCase = true) },
    )
    val buffer = allocArray<UShortVar>(entries.sumOf { it.length + 1 } + if (entries.isEmpty()) 2 else 1)
    var offset = 0
    for (entry in entries) {
        entry.forEach { character -> buffer[offset++] = character.code.toUShort() }
        buffer[offset++] = 0u
    }
    buffer[offset] = 0u
    if (entries.isEmpty()) buffer[offset + 1] = 0u
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

private fun Path.windowsPath(): String = toString().replace('/', '\\')
