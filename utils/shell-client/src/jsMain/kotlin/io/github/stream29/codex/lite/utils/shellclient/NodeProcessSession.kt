package io.github.stream29.codex.lite.utils.shellclient

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
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared lifecycle for Node pipe and PTY transports. */
internal class NodeProcessSession(
    parentScope: CoroutineScope,
    private val writeInput: suspend (String) -> Unit,
    private val closeInput: suspend () -> Unit,
    private val terminate: suspend () -> Unit,
    private val release: () -> Unit,
) : ProcessSession {
    private val sessionJob: CompletableJob = SupervisorJob(parentScope.coroutineContext[Job])
    override val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    override val stdin: SendChannel<String>
        field = StdinChannel(scope, sessionJob)
    override val stdout: StdoutBuffer
        field = MutableStdoutBuffer(scope)
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val terminationRequested: CompletableDeferred<Unit> = CompletableDeferred()
    private val pendingOutput: MutableSet<Job> = mutableSetOf()

    private val cancellationGuard: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
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

    override fun close() {
        if (exitCode.isCompleted || !terminationRequested.complete(Unit)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val cancellation = processCancellation()
            stdin.abort(cancellation)
            try {
                terminate()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    /** Accepts one Node output event and resumes its source after buffering settles. */
    internal fun acceptOutput(bytes: ByteArray, resumeSource: () -> Unit) {
        if (!scope.isActive) {
            resumeSource()
            return
        }
        val outputJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (bytes.isNotEmpty()) {
                    sessionJob.ensureActive()
                    stdout.send(bytes)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
            } finally {
                resumeSource()
            }
        }
        pendingOutput += outputJob
        outputJob.invokeOnCompletion { pendingOutput -= outputJob }
        outputJob.start()
    }

    /** Completes only after every earlier Node output callback has settled. */
    internal fun acceptExit(exitCode: Int) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                pendingOutput.toList().joinAll()
                complete(exitCode)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    internal fun acceptFailure(failure: Throwable) {
        fail(failure)
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
            val next = stdin.receiveCatching().getOrNull() ?: break
            if (next.written.isCompleted) continue

            try {
                writeInput(next.text)
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
            closeInput()
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
                val cancellation = processCancellation()
                exitCode.completeExceptionally(cancellation)
                stdin.abort(cancellation)
                try {
                    terminate()
                } catch (_: Throwable) {
                    // Resource release below is still required after a failed termination request.
                }
                stdout.abort(cancellation)
            }
            try {
                release()
            } catch (_: Throwable) {
                // The session is terminal; platform cleanup is best effort.
            }
        }
    }

    private fun processCancellation(cause: Throwable? = null): CancellationException =
        CancellationException(cause?.message ?: "Process session is closed.")
}
