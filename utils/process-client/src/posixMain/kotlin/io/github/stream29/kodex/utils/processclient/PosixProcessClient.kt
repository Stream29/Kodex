@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    io.github.stream29.kodex.utils.processclient.NativeProcessAdapterApi::class,
)

package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_create_pipe
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_spawn_process
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.close
import platform.posix.errno

internal actual class PlatformProcessClient actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    ProcessClient {

    actual override suspend fun start(command: ProcessCommand): ProcessSession =
        this@PlatformProcessClient.acquireProcessSession(Dispatchers.IO) {
            this@PlatformProcessClient.requireOpen()
            command.startPosixProcess(this@PlatformProcessClient)
        }

    actual override fun close() {
        cancel()
    }
}

private suspend fun ProcessCommand.startPosixProcess(ownerScope: CoroutineScope): ProcessSession {
    val unadopted = mutableListOf<Int>()
    val streams = mutableListOf<NativeProcessStream>()
    var child: NativeProcessExit? = null

    fun pipe(): Pair<Int, Int> = memScoped {
        val descriptors = allocArray<IntVar>(2)
        if (kodex_process_create_pipe(descriptors) != 0) {
            throw ProcessException("Failed to create process pipe: errno $errno.")
        }
        val read = descriptors[0]
        val write = descriptors[1]
        unadopted.add(read)
        unadopted.add(write)
        read to write
    }

    fun adopt(fd: Int): NativeProcessStream =
        adoptNativeProcessStream(fd.toLong(), terminal = false).also {
            unadopted.remove(fd)
            streams.add(it)
        }

    fun closeUnadopted() {
        unadopted.forEach { close(it) }
        unadopted.clear()
    }

    try {
        val (stdinRead, stdinWrite) = pipe()
        val (outputRead, outputWrite) = pipe()
        val (errorRead, errorWrite) = pipe()
        // Initialize the reactor and adopt all parent ends before spawning. The child's
        // ends are distinct open file descriptions and keep their blocking flags.
        val stdin = adopt(stdinWrite)
        val stdout = adopt(outputRead)
        val stderr = adopt(errorRead)
        val exit = arguments.withPosixStrings { argumentValues, argumentCount ->
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
                            throw ProcessException("Failed to start process with $executable: error $result.")
                        }
                        val root = pid.value.toLong()
                        watchNativeProcessExit(root, if (processGroup.value != 0) root else 0L)
                    }
                }
        }
        child = exit
        closeUnadopted()
        return PosixProcessSession(stdin, stdout, stderr, exit, ownerScope)
    } catch (failure: Throwable) {
        closeUnadopted()
        child?.terminate()
        streams.forEach { it.requestClose() }
        withContext(NonCancellable) {
            for (stream in streams) suppressCleanup(failure) { stream.close() }
            suppressCleanup(failure) { child?.close() }
        }
        throw failure
    }
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

private class PosixProcessSession(
    private val processStdin: NativeProcessStream,
    private val processStdout: NativeProcessStream,
    private val processStderr: NativeProcessStream,
    private val processExit: NativeProcessExit,
    ownerScope: CoroutineScope,
) : ProcessSession {
    override val stdin: CoroutineRawSink = processStdin
    override val stdout: CoroutineRawSource = processStdout
    override val stderr: CoroutineRawSource = processStderr
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closeRequested = CompletableDeferred<Unit>()
    private val releaseResult = CompletableDeferred<Result<Unit>>()

    // This child exists even if cancellation races with session handoff. Its
    // owner cannot finish until pending I/O is invalidated and the child reaped.
    private val lifecycle = ownerScope.launch(start = CoroutineStart.ATOMIC) {
        try {
            publishExit()
            closeRequested.await()
        } finally {
            close()
            withContext(NonCancellable) {
                var cleanup: Throwable? = null
                suspend fun release(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        val first = cleanup
                        if (first == null) cleanup = failure
                        else if (first !== failure) first.addSuppressed(failure)
                    }
                }
                release { processStdin.close() }
                release { processStdout.close() }
                release { processStderr.close() }
                release { processExit.close() }
                publishExit()
                releaseResult.complete(cleanup?.let { Result.failure(it) } ?: Result.success(Unit))
            }
        }
    }

    override fun close() {
        processExit.terminate()
        processStdin.requestClose()
        processStdout.requestClose()
        processStderr.requestClose()
        closeRequested.complete(Unit)
    }

    override suspend fun closeAndJoin(): Unit = withContext(NonCancellable) {
        close()
        lifecycle.join()
        releaseResult.await().getOrThrow()
    }

    private suspend fun publishExit() {
        if (exitCode.isCompleted) return
        try {
            exitCode.complete(processExit.await())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            exitCode.completeExceptionally(failure)
        }
    }
}

private suspend inline fun suppressCleanup(primary: Throwable, action: () -> Unit) {
    try {
        action()
    } catch (failure: Throwable) {
        if (failure !== primary) primary.addSuppressed(failure)
    }
}
