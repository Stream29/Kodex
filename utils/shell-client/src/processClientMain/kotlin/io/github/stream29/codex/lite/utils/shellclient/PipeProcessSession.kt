package io.github.stream29.codex.lite.utils.shellclient

import io.github.stream29.codex.lite.utils.processclient.ProcessClient
import io.github.stream29.codex.lite.utils.processclient.ProcessCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

internal expect val ShellPipeIoDispatcher: CoroutineDispatcher

internal suspend fun CoroutineScope.startPipeProcess(
    client: ProcessClient,
    invocation: ShellInvocation,
    command: ShellProcessCommand,
): ProcessSession {
    val process = client.start(
        ProcessCommand(
            executable = invocation.executable,
            arguments = invocation.argumentsBeforeCommand + invocation.command,
            workingDirectory = command.workingDirectory,
            environment = command.environment,
        ),
    )
    return PipeProcessSession(process, this)
}

private class PipeProcessSession(
    private val process: io.github.stream29.codex.lite.utils.processclient.ProcessSession,
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

    private val stdinWriter: Job = scope.launch(ShellPipeIoDispatcher) {
        try {
            consumeStdin()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private val outputReaders: List<Job> = listOf(
        scope.launch(ShellPipeIoDispatcher) {
            readOutput(process.stdout, ::emitStandardOutput)
        },
        scope.launch(ShellPipeIoDispatcher) {
            readOutput(process.stderr, ::emitStandardError)
        },
    )

    init {
        scope.launch {
            try {
                val code = process.exitCode.await()
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
        scope.launch(ShellPipeIoDispatcher, start = CoroutineStart.UNDISPATCHED) {
            val cancellation = processCancellation()
            stdin.abort(cancellation)
            try {
                process.close()
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

    private suspend fun complete(code: Int) {
        stdout.close()
        standardOutput.close()
        standardError.close()
        stdout.flush()
        standardOutput.flush()
        standardError.flush()
        stdin.close()
        if (!exitCode.complete(code)) return

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
            val next = stdin.receiveCatching().getOrNull() ?: break
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
            process.stdin.close()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private fun writeStdin(text: String) {
        val input = Buffer().apply { write(text.encodeToByteArray()) }
        process.stdin.write(input, input.size)
        process.stdin.flush()
    }

    private suspend fun readOutput(
        source: RawSource,
        emit: suspend (ByteArray) -> Unit,
    ) {
        try {
            val output = Buffer()
            while (true) {
                val count = source.readAtMostTo(output, ProcessIoChunkSize)
                if (count < 0L) break
                if (count > 0L) emit(output.readByteArray(count.toInt()))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IOException) {
            if (!terminationRequested.isCompleted && !process.exitCode.isCompleted) {
                fail(ProcessException("Failed to read process output.", failure))
            }
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private suspend fun releaseAfterSessionEnd() {
        withContext(NonCancellable + ShellPipeIoDispatcher) {
            if (exitCode.isCompleted && !exitCode.isCancelled) {
                stdout.finish()
                standardOutput.finish()
                standardError.finish()
            } else {
                val cancellation = processCancellation()
                exitCode.completeExceptionally(cancellation)
                stdin.abort(cancellation)
                stdout.abort(cancellation)
                standardOutput.abort(cancellation)
                standardError.abort(cancellation)
            }
            runCatching { process.close() }
        }
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.")
}

private const val ProcessIoChunkSize: Long = 8_192L
