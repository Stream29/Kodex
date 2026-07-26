package io.github.stream29.codex.lite.utils.shellclient

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

public actual class ShellClient actual constructor() :
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default),
    AutoCloseable {

    public actual suspend fun start(command: ShellProcessCommand): ProcessSession =
        withContext(Dispatchers.IO) {
            this@ShellClient.requireOpen()
            JvmProcess(command.startProcess(), command.tty, this@ShellClient)
        }

    public actual override fun close() {
        cancel()
    }
}

private fun ShellProcessCommand.startProcess(): Process {
    if (command.isBlank()) {
        throw ProcessException("Process command must not be blank.")
    }
    val invocation = shell.invocation(command, login)
    return try {
        val processCommand = listOf(invocation.executable) + invocation.argumentsBeforeCommand + invocation.command
        if (tty) {
            PtyProcessBuilder(processCommand.toTypedArray())
                .setDirectory(workingDirectory.toString())
                .setEnvironment(
                    System.getenv().toMutableMap().apply {
                        putAll(environment)
                        putIfAbsent("TERM", "xterm-256color")
                    },
                )
                .setInitialColumns(DefaultPtyColumns)
                .setInitialRows(DefaultPtyRows)
                .setRedirectErrorStream(true)
                .setUseWinConPty(true)
                .start()
        } else {
            ProcessBuilder(processCommand)
                .redirectErrorStream(false)
                .directory(File(workingDirectory.toString()))
                .apply {
                    environment().putAll(this@startProcess.environment)
                }
                .start()
        }
    } catch (error: IOException) {
        throw ProcessException("Failed to start process with ${invocation.executable}.", error)
    }
}

private class JvmProcess(
    private val process: Process,
    private val tty: Boolean,
    parentScope: CoroutineScope,
) : ProcessSession {
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

    private val stdinWriter: Job = scope.launch(Dispatchers.IO) {
        try {
            consumeStdin()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private val outputReaders: List<Job> = listOf(
        scope.launch(Dispatchers.IO) {
            readOutput(process.inputStream, ::emitStandardOutput)
        },
        scope.launch(Dispatchers.IO) {
            readOutput(process.errorStream, ::emitStandardError)
        },
    )

    init {
        scope.launch(Dispatchers.IO) {
            try {
                val code = process.waitFor()
                outputReaders.joinAll()
                complete(code)
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

    private suspend fun emitStandardOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        sessionJob.ensureActive()
        stdout.send(bytes)
        standardOutput.send(bytes)
    }

    private suspend fun emitStandardError(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        sessionJob.ensureActive()
        stdout.send(bytes)
        standardError.send(bytes)
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
        withContext(Dispatchers.IO) {
            try {
                val input = if (tty) {
                    val enter = (process as PtyProcess).enterKeyCode.toInt().toChar()
                    text.replace("\r\n", enter.toString()).replace('\n', enter)
                } else {
                    text
                }
                process.outputStream.write(input.encodeToByteArray())
                process.outputStream.flush()
            } catch (error: IOException) {
                throw ProcessException("Failed to write to process standard input.", error)
            }
        }

    private suspend fun readOutput(
        input: InputStream,
        emit: suspend (ByteArray) -> Unit,
    ) {
        try {
            val bytes = ByteArray(8_192)
            while (true) {
                val count = try {
                    input.read(bytes)
                } catch (error: IOException) {
                    if (terminationRequested.isCompleted || !process.isAlive) break
                    throw ProcessException("Failed to read process output.", error)
                }
                if (count < 0) break
                if (count > 0) emit(bytes.copyOf(count))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private suspend fun closeStdin(): Unit =
        withContext(Dispatchers.IO) {
            if (tty) return@withContext
            try {
                process.outputStream.close()
            } catch (error: IOException) {
                throw ProcessException("Failed to close process standard input.", error)
            }
        }

    private suspend fun terminateProcessTree(): Unit = withContext(Dispatchers.IO) {
        try {
            // pty4j deliberately does not implement Process.toHandle(), but it
            // does expose the child PID on every supported backend.
            ProcessHandle.of(process.pid()).orElse(null)?.descendants()?.use { descendants ->
                descendants.forEach { descendant -> descendant.destroyForcibly() }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun releaseResources(): Unit = withContext(Dispatchers.IO) {
        process.outputStream.closeQuietly()
        process.inputStream.closeQuietly()
        process.errorStream.closeQuietly()
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.")

    private fun java.io.Closeable.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // A closed child stream has no meaningful recovery path.
        }
    }
}
