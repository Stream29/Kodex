@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.codex.lite.utils.processclient

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
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import platform.windows.AssignProcessToJobObject
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CREATE_SUSPENDED
import platform.windows.CREATE_UNICODE_ENVIRONMENT
import platform.windows.CloseHandle
import platform.windows.CreateJobObjectW
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INFINITE
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.ResumeThread
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.SetHandleInformation
import platform.windows.TerminateJobObject
import platform.windows.TerminateProcess
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public actual class ProcessClient internal actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    AutoCloseable {

    public actual suspend fun start(command: ProcessCommand): ProcessSession =
        withContext(WindowsProcessIoDispatcher) {
            this@ProcessClient.requireOpen()
            command.startWindowsProcess(this@ProcessClient)
        }

    public actual override fun close() {
        cancel()
    }
}

private val WindowsProcessIoDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(64, "CodexLite.ProcessClientIO")

private fun ProcessCommand.startWindowsProcess(ownerScope: CoroutineScope): ProcessSession = memScoped {
    val securityAttributes = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
        lpSecurityDescriptor = null
        bInheritHandle = 1
    }
    withWindowsPipe(securityAttributes.ptr) { stdinRead, stdinWrite ->
        withWindowsPipe(securityAttributes.ptr) { outputRead, outputWrite ->
            withWindowsPipe(securityAttributes.ptr) { errorRead, errorWrite ->
                checkWindowsSuccess(
                    SetHandleInformation(stdinWrite, HANDLE_FLAG_INHERIT.toUInt(), 0u),
                    "make parent stdin handle non-inheritable",
                )
                checkWindowsSuccess(
                    SetHandleInformation(outputRead, HANDLE_FLAG_INHERIT.toUInt(), 0u),
                    "make parent stdout handle non-inheritable",
                )
                checkWindowsSuccess(
                    SetHandleInformation(errorRead, HANDLE_FLAG_INHERIT.toUInt(), 0u),
                    "make parent stderr handle non-inheritable",
                )

                val startupInfo = alloc<STARTUPINFOW>().apply {
                    cb = sizeOf<STARTUPINFOW>().toUInt()
                    lpReserved = null
                    lpDesktop = null
                    lpTitle = null
                    dwX = 0u
                    dwY = 0u
                    dwXSize = 0u
                    dwYSize = 0u
                    dwXCountChars = 0u
                    dwYCountChars = 0u
                    dwFillAttribute = 0u
                    dwFlags = STARTF_USESTDHANDLES.toUInt()
                    wShowWindow = 0u
                    cbReserved2 = 0u
                    lpReserved2 = null
                    hStdInput = stdinRead
                    hStdOutput = outputWrite
                    hStdError = errorWrite
                }
                val processInfo = alloc<PROCESS_INFORMATION>().apply {
                    hProcess = null
                    hThread = null
                    dwProcessId = 0u
                    dwThreadId = 0u
                }
                val environmentBlock = windowsEnvironmentBlock(environment)
                checkWindowsSuccess(
                    CreateProcessW(
                        null,
                        windowsStringBuffer(windowsCommandLine()),
                        null,
                        null,
                        1,
                        CREATE_NO_WINDOW.toUInt() or
                            CREATE_SUSPENDED.toUInt() or
                            CREATE_UNICODE_ENVIRONMENT.toUInt(),
                        environmentBlock,
                        workingDirectory.windowsPath(),
                        startupInfo.ptr,
                        processInfo.ptr,
                    ),
                    "start process",
                )

                val process = WindowsHandle(requireNotNull(processInfo.hProcess))
                val thread = requireNotNull(processInfo.hThread)
                val job = try {
                    createWindowsProcessJob(process.value)
                } catch (failure: Throwable) {
                    TerminateProcess(process.value, 1u)
                    process.close()
                    closeWindowsHandle(thread)
                    throw failure
                }
                try {
                    if (ResumeThread(thread) == UInt.MAX_VALUE) {
                        throw ProcessException("Failed to resume process: error ${GetLastError()}.")
                    }
                    WindowsPipeTransfer(
                        value = WindowsProcessSession(
                            process = process,
                            job = job,
                            stdinHandle = WindowsHandle(stdinWrite),
                            stdoutHandle = WindowsHandle(outputRead),
                            stderrHandle = WindowsHandle(errorRead),
                            ownerScope = ownerScope,
                        ),
                        transferRead = true,
                    )
                } catch (failure: Throwable) {
                    TerminateJobObject(job.value, 1u)
                    job.close()
                    process.close()
                    throw failure
                } finally {
                    closeWindowsHandle(thread)
                }
            }.let { session -> WindowsPipeTransfer(session, transferRead = true) }
        }.let { session -> WindowsPipeTransfer(session, transferWrite = true) }
    }
}

private data class WindowsPipeTransfer<T>(
    val value: T,
    val transferRead: Boolean = false,
    val transferWrite: Boolean = false,
)

private fun <T> withWindowsPipe(
    securityAttributes: CPointer<SECURITY_ATTRIBUTES>,
    block: (
        read: CPointer<out CPointed>,
        write: CPointer<out CPointed>,
    ) -> WindowsPipeTransfer<T>,
): T = memScoped {
    val read = alloc<COpaquePointerVar>()
    val write = alloc<COpaquePointerVar>()
    checkWindowsSuccess(CreatePipe(read.ptr, write.ptr, securityAttributes, 0u), "create process pipe")
    val readHandle = requireNotNull(read.value)
    val writeHandle = requireNotNull(write.value)
    try {
        val transfer = block(readHandle, writeHandle)
        if (!transfer.transferRead) closeWindowsHandle(readHandle)
        if (!transfer.transferWrite) closeWindowsHandle(writeHandle)
        transfer.value
    } catch (failure: Throwable) {
        closeWindowsHandle(readHandle)
        closeWindowsHandle(writeHandle)
        throw failure
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class WindowsProcessSession(
    private val process: WindowsHandle,
    private val job: WindowsHandle,
    stdinHandle: WindowsHandle,
    stdoutHandle: WindowsHandle,
    stderrHandle: WindowsHandle,
    ownerScope: CoroutineScope,
) : ProcessSession {
    override val stdin: RawSink = WindowsRawSink(stdinHandle)
    override val stdout: RawSource = WindowsRawSource(stdoutHandle)
    override val stderr: RawSource = WindowsRawSource(stderrHandle)
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val cancellationGuard = ownerScope.lazyProcessCancellationGuard(::close)

    init {
        cancellationGuard.start()
        ownerScope.launch(WindowsProcessIoDispatcher) {
            try {
                exitCode.complete(awaitExitCode())
            } catch (failure: Throwable) {
                exitCode.completeExceptionally(failure)
            } finally {
                process.close()
                job.close()
            }
        }.invokeOnCompletion { failure ->
            if (failure != null) close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        cancellationGuard.cancel()
        if (!exitCode.isCompleted) {
            TerminateJobObject(job.value, 1u)
        }
        stdin.close()
        stdout.close()
        stderr.close()
    }

    private fun awaitExitCode(): Int {
        if (WaitForSingleObject(process.value, INFINITE) != WAIT_OBJECT_0) {
            throw ProcessException("Failed to observe process exit: error ${GetLastError()}.")
        }
        return memScoped {
            val code = alloc<UIntVar>()
            checkWindowsSuccess(GetExitCodeProcess(process.value, code.ptr), "read process exit code")
            code.value.toInt()
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class WindowsHandle(
    val value: CPointer<out CPointed>,
) {
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean
        get() = !closed.load()

    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            closeWindowsHandle(value)
        }
    }
}

private class WindowsRawSource(
    private val handle: WindowsHandle,
) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount must not be negative." }
        if (byteCount == 0L) return 0L
        if (!handle.isOpen) throw IOException("Process output is closed.")

        val bytes = ByteArray(minOf(byteCount, ProcessIoChunkSize).toInt())
        val count = memScoped {
            val read = alloc<UIntVar>()
            val success = bytes.usePinned { pinned ->
                ReadFile(handle.value, pinned.addressOf(0), bytes.size.toUInt(), read.ptr, null)
            }
            if (success == 0 && GetLastError() == ERROR_BROKEN_PIPE.toUInt()) return@memScoped -1
            checkWindowsSuccess(success, "read process output")
            read.value.toInt()
        }
        if (count < 0) return -1L
        if (count == 0) return -1L
        sink.write(bytes, startIndex = 0, endIndex = count)
        return count.toLong()
    }

    override fun close() {
        handle.close()
    }
}

private class WindowsRawSink(
    private val handle: WindowsHandle,
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
                if (!handle.isOpen) throw IOException("Process input is closed.")
                val count = memScoped {
                    val written = alloc<UIntVar>()
                    checkWindowsSuccess(
                        bytes.usePinned { pinned ->
                            WriteFile(
                                handle.value,
                                pinned.addressOf(offset),
                                (bytes.size - offset).toUInt(),
                                written.ptr,
                                null,
                            )
                        },
                        "write process input",
                    )
                    written.value.toInt()
                }
                if (count == 0) throw IOException("Process input accepted no bytes.")
                offset += count
            }
            remaining -= bytes.size
        }
    }

    override fun flush() = Unit

    override fun close() {
        handle.close()
    }
}

private fun ProcessCommand.windowsCommandLine(): String =
    (listOf(executable) + arguments).joinToString(" ") { argument -> argument.quoteWindowsArgument() }

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
): CPointer<UShortVar> {
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

private fun Path.windowsPath(): String = toString().replace('/', '\\')

private fun createWindowsProcessJob(process: CPointer<out CPointed>): WindowsHandle {
    val job = CreateJobObjectW(null, null)
        ?: throw ProcessException("Failed to create process Job Object: error ${GetLastError()}.")
    if (AssignProcessToJobObject(job, process) == 0) {
        closeWindowsHandle(job)
        throw ProcessException("Failed to assign process Job Object: error ${GetLastError()}.")
    }
    return WindowsHandle(job)
}

private fun checkWindowsSuccess(success: Int, operation: String) {
    if (success == 0) {
        throw ProcessException("Failed to $operation: error ${GetLastError()}.")
    }
}

private fun closeWindowsHandle(handle: CPointer<out CPointed>) {
    CloseHandle(handle)
}

private const val ProcessIoChunkSize: Long = 8_192L
