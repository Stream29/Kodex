@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    io.github.stream29.kodex.utils.processclient.NativeProcessAdapterApi::class,
)

package io.github.stream29.kodex.utils.shellclient

import io.github.stream29.kodex.utils.processclient.NativeProcessExit
import io.github.stream29.kodex.utils.processclient.NativeProcessStream
import io.github.stream29.kodex.utils.processclient.NativeReadyProcessStream
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.adoptNativeProcessStream
import io.github.stream29.kodex.utils.processclient.watchNativeProcessExit
import io.github.stream29.kodex.utils.shellclient.cinterop.kodex_open_shell_pty
import io.github.stream29.kodex.utils.shellclient.cinterop.kodex_spawn_pty_shell
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import platform.posix.close

internal actual class PlatformShellClient actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    ShellClient {
    private val processClient = scope.ProcessClient()

    actual override suspend fun start(command: ShellProcessCommand): ProcessSession =
        this@PlatformShellClient.acquireShellSession(Dispatchers.IO) {
            this@PlatformShellClient.requireOpen()
            if (command.command.isBlank()) throw ProcessException("Process command must not be blank.")
            if (command.tty) {
                startPosixPtyProcess(
                    invocation = command.shell.invocation(
                        command = command.command,
                        workingDirectory = command.workingDirectory,
                        login = command.login,
                    ),
                    environment = command.environment,
                    parentScope = this@PlatformShellClient,
                )
            } else {
                this@PlatformShellClient.startPipeProcess(
                    client = processClient,
                    invocation = command.shell.invocation(command.command, command.login),
                    command = command,
                )
            }
        }

    actual override fun close() {
        cancel()
    }
}

private suspend fun startPosixPtyProcess(
    invocation: ShellInvocation,
    environment: Map<String, String>,
    parentScope: CoroutineScope,
): ProcessSession {
    val (master, slave) = memScoped {
        val masterFd = alloc<IntVar>()
        val slaveFd = alloc<IntVar>()
        val result = kodex_open_shell_pty(masterFd.ptr, slaveFd.ptr)
        if (result != 0) throw ProcessException("Failed to open PTY: error $result.")
        masterFd.value to slaveFd.value
    }
    var endpoint: NativeProcessStream? = null
    var exit: NativeProcessExit? = null
    var slaveOpen = true
    try {
        // Adopt before fork so failed reactor setup cannot leave a child to reap.
        val terminal = adoptNativeProcessStream(master.toLong(), terminal = true)
        endpoint = terminal
        val readyTerminal = terminal as NativeReadyProcessStream
        val child = environment.withPosixEnvironmentOverrides { environmentOverrides, environmentOverrideCount ->
            memScoped {
                val pid = alloc<IntVar>()
                val result = kodex_spawn_pty_shell(
                    pid = pid.ptr,
                    master_fd = master,
                    slave_fd = slave,
                    environment_overrides = environmentOverrides,
                    environment_override_count = environmentOverrideCount,
                    shell = invocation.executable,
                    first_argument = invocation.argumentsBeforeCommand.getOrNull(0),
                    second_argument = invocation.argumentsBeforeCommand.getOrNull(1),
                    command = invocation.command,
                )
                if (result != 0) {
                    throw ProcessException("Failed to start PTY with ${invocation.executable}: error $result.")
                }
                val root = pid.value.toLong()
                watchNativeProcessExit(root, root)
            }
        }
        exit = child
        close(slave)
        slaveOpen = false
        return PosixPtySession(readyTerminal, child, parentScope)
    } catch (failure: Throwable) {
        if (slaveOpen) close(slave)
        if (endpoint == null) close(master)
        exit?.terminate()
        withContext(NonCancellable) {
            suppressPtyCleanup(failure) { endpoint?.close() }
            suppressPtyCleanup(failure) { exit?.close() }
        }
        throw failure
    }
}

private inline fun <T> Map<String, String>.withPosixEnvironmentOverrides(
    block: (overrides: CPointer<CPointerVar<ByteVar>>?, count: ULong) -> T,
): T {
    if (isEmpty()) return block(null, 0uL)
    return memScoped {
        val overrides = allocArray<CPointerVar<ByteVar>>(size)
        entries.forEachIndexed { index, (name, value) ->
            val encoded = "$name=$value".encodeToByteArray()
            val entry = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { byteIndex, byte -> entry[byteIndex] = byte }
            entry[encoded.size] = 0
            overrides[index] = entry
        }
        block(overrides, size.toULong())
    }
}

private class PosixPtySession(
    private val terminal: NativeReadyProcessStream,
    private val child: NativeProcessExit,
    parentScope: CoroutineScope,
) : ProcessSession {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
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
    private val terminationRequested = CompletableDeferred<Unit>()

    private val cancellationGuard = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            awaitCancellation()
        } finally {
            releaseAfterSessionEnd()
        }
    }
    private val stdinWriter = scope.launch { consumeStdin() }
    private val outputReader = scope.launch { consumeOutput() }

    init {
        scope.launch {
            try {
                val code = child.await()
                // Do not wait for a descendant to close the slave. Keep this
                // same reader alive until it has drained the available tail.
                terminal.finishReadsWithoutWaiting()
                stdin.close()
                stdinWriter.cancel()
                outputReader.join()
                stdout.flush()
                standardOutput.flush()
                standardError.flush()
                terminal.close()
                child.close()
                stdout.signalTerminal()
                standardOutput.signalTerminal()
                standardError.signalTerminal()
                exitCode.complete(code)
                cancellationGuard.cancel()
                sessionJob.complete()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    override fun close() {
        if (exitCode.isCompleted || !terminationRequested.complete(Unit)) return
        child.terminate()
        val cancellation = processCancellation()
        try {
            stdin.abort(cancellation)
        } finally {
            // Cancel just the pending write, not the shared master (output still drains).
            stdinWriter.cancel(cancellation)
        }
    }

    private suspend fun consumeStdin() {
        // Closing this channel only stops input admission. Explicit EOT bytes
        // are delivered unchanged to the terminal line discipline.
        while (true) {
            val next = stdin.receiveCatching().getOrNull() ?: return
            if (next.written.isCompleted) continue
            try {
                val input = Buffer().apply { write(next.text.encodeToByteArray()) }
                terminal.write(input, input.size)
                stdin.succeed(next)
            } catch (failure: CancellationException) {
                stdin.fail(next, failure)
                throw failure
            } catch (failure: IOException) {
                // The slave can disappear before its exit notification reaches
                // Kotlin. Settle the failed input without overriding root status.
                stdin.fail(next, failure)
                stdin.abort(failure)
                return
            } catch (failure: Throwable) {
                stdin.fail(next, failure)
                fail(failure)
                return
            }
        }
    }

    private suspend fun consumeOutput() {
        try {
            val output = Buffer()
            while (true) {
                val count = terminal.readAtMostTo(output, 8_192L)
                if (count < 0L) return
                val chunk = output.readByteArray(count.toInt())
                stdout.send(chunk)
                standardOutput.send(chunk)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private fun fail(failure: Throwable) {
        if (exitCode.completeExceptionally(failure)) sessionJob.cancel(processCancellation(failure))
    }

    private suspend fun releaseAfterSessionEnd() = withContext(NonCancellable) {
        if (exitCode.isCompleted && !exitCode.isCancelled) {
            stdout.finish()
            standardOutput.finish()
            standardError.finish()
        } else {
            @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
            val cancellation = sessionJob.getCancellationException()
            exitCode.completeExceptionally(cancellation)
            child.terminate()
            terminal.requestClose()
            val primary = runCatching { exitCode.await() }.exceptionOrNull() ?: cancellation
            // A caller-supplied stdin close handler must not bypass native release.
            suppressPtyCleanup(primary) { stdin.abort(cancellation) }
            suppressPtyCleanup(primary) { stdout.abort(cancellation) }
            suppressPtyCleanup(primary) { standardOutput.abort(cancellation) }
            suppressPtyCleanup(primary) { standardError.abort(cancellation) }
            suppressPtyCleanup(primary) { terminal.close() }
            suppressPtyCleanup(primary) { child.close() }
        }
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.", cause)
}

private suspend inline fun suppressPtyCleanup(primary: Throwable, action: () -> Unit) {
    try {
        action()
    } catch (failure: Throwable) {
        if (failure !== primary) primary.addSuppressed(failure)
    }
}
