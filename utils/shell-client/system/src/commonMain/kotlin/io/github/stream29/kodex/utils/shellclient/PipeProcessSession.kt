package io.github.stream29.kodex.utils.shellclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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

@OptIn(ExperimentalAtomicApi::class)
internal class PipeProcessSession(
    private val process: io.github.stream29.kodex.utils.processclient.ProcessSession,
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
    private val stdinWriterPhase = AtomicReference<StdinWriterPhase>(StdinWriterPhase.BetweenWrites)
    private val outputEofCount = AtomicInt(0)
    private var primaryFailure: Throwable? = null

    private val cancellationGuard: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } catch (failure: CancellationException) {
            if (primaryFailure == null && !exitCode.isCompleted) {
                // Read the cancellation admitted by the actual session Job,
                // not its debug-recovered copy thrown by awaitCancellation.
                @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
                val admitted = sessionJob.getCancellationException()
                primaryFailure = admitted
            }
            throw failure
        } finally {
            releaseAfterSessionEnd()
        }
    }

    private val stdinWriter: Job = scope.launch {
        try {
            consumeStdin()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private val outputReaders: List<Job> = listOf(
        scope.launch {
            readOutput(process.stdout, ::emitStandardOutput)
        },
        scope.launch {
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
        scope.launch {
            val cancellation = processCancellation()
            stdin.abort(cancellation)
            try {
                process.closeAndJoin()
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
            if (outputEofCount.load() == outputReaders.size && !terminationRequested.isCompleted) {
                stopStdinAfterEof()
            }
            stdinWriter.join()
            cancellationGuard.cancel()
            cancellationGuard.join()
            sessionJob.complete()
        }
    }

    private fun stopStdinAfterEof() {
        val cause = ClosedSendChannelException("Process standard input is closed.")
        val previous = stdinWriterPhase.exchange(StdinWriterPhase.Stopped(cause))
        stdin.abort(cause)
        // A receive may already have taken a value without resuming to claim it.
        // Let that receiver run; cancelling it could leave the sender's write unacknowledged.
        if (previous === StdinWriterPhase.Writing) stdinWriter.cancel()
    }

    private fun fail(failure: Throwable) {
        if (exitCode.completeExceptionally(failure)) {
            primaryFailure = failure
            sessionJob.cancel(processCancellation(failure))
        }
    }

    private suspend fun consumeStdin() {
        while (stdinWriterPhase.load() !is StdinWriterPhase.Stopped) {
            val next = stdin.receiveCatching().getOrNull() ?: break
            if (next.written.isCompleted) continue
            if (!stdinWriterPhase.compareAndSet(StdinWriterPhase.BetweenWrites, StdinWriterPhase.Writing)) {
                val stopped = stdinWriterPhase.load() as StdinWriterPhase.Stopped
                stdin.fail(next, stopped.cause)
                break
            }

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
            } finally {
                // If shutdown changed Writing to Stopped, do not enter a new receive.
                stdinWriterPhase.compareAndSet(StdinWriterPhase.Writing, StdinWriterPhase.BetweenWrites)
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

    private suspend fun writeStdin(text: String) {
        val input = Buffer().apply { write(text.encodeToByteArray()) }
        process.stdin.write(input, input.size)
        process.stdin.flush()
    }

    private suspend fun readOutput(
        source: CoroutineRawSource,
        emit: suspend (ByteArray) -> Unit,
    ) {
        try {
            val output = Buffer()
            while (true) {
                val count = source.readAtMostTo(output, ProcessIoChunkSize)
                if (count < 0L) {
                    outputEofCount.fetchAndAdd(1)
                    break
                }
                if (count > 0L) emit(output.readByteArray(count.toInt()))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IOException) {
            if (!terminationRequested.isCompleted && !process.exitCode.isCompleted) {
                fail(ProcessException("Failed to read process output.", failure))
            }
        } catch (failure: Throwable) {
            if (!terminationRequested.isCompleted) fail(failure)
        }
    }

    private suspend fun releaseAfterSessionEnd() {
        withContext(NonCancellable) {
            if (primaryFailure == null && exitCode.isCompleted && exitCode.isCancelled) {
                // Deferred completion can wake a cancelling consumer before
                // fail() has published primaryFailure on this worker.
                runCatching { exitCode.await() }.exceptionOrNull()?.let { primaryFailure = it }
            }
            if (exitCode.isCompleted && !exitCode.isCancelled) {
                stdout.finish()
                standardOutput.finish()
                standardError.finish()
            } else {
                val cancellation = primaryFailure as? CancellationException ?: processCancellation()
                exitCode.completeExceptionally(cancellation)
                stdin.abort(cancellation)
                stdout.abort(cancellation)
                standardOutput.abort(cancellation)
                standardError.abort(cancellation)
            }
            try {
                process.closeAndJoin()
            } catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary != null) {
                    if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.")

    private sealed interface StdinWriterPhase {
        data object BetweenWrites : StdinWriterPhase
        data object Writing : StdinWriterPhase
        class Stopped(val cause: ClosedSendChannelException) : StdinWriterPhase
    }
}

private const val ProcessIoChunkSize: Long = 8_192L
