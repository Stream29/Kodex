package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/** One direct executable invocation without an intermediate shell. */
public data class ProcessCommand(
    public val executable: String,
    public val arguments: List<String> = emptyList(),
    /** [Path](`.`) inherits the host process working directory. */
    public val workingDirectory: Path = Path("."),
    /** Overrides layered over the inherited host environment. */
    public val environment: Map<String, String> = emptyMap(),
)

/**
 * Direct child process with independent raw standard input/output/error pipes.
 *
 * Stream reads, writes, flushes and closes suspend rather than blocking the
 * caller's dispatcher. Streams remain owned by this session and its client:
 * closing/cancelling the client invalidates its sessions and streams.
 * Stream failures are platform-specific I/O failures, not uniformly
 * [ProcessException]. [exitCode] publishes the final observed process status.
 */
public interface ProcessSession : AutoCloseable {
    public val stdin: CoroutineRawSink
    public val stdout: CoroutineRawSource
    public val stderr: CoroutineRawSource
    public val exitCode: Deferred<Int>

    /**
     * Requests process-tree termination and releases its streams. This operation
     * is idempotent; it can wait synchronously on some platforms. Await [exitCode]
     * for the resulting final status, not a synthetic cancellation status.
     */
    override fun close()
}

/** Failure raised by a direct process implementation. */
public class ProcessException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Stateful owner for direct child processes using ordinary byte pipes.
 *
 * Scope factories create an independently cancellable child of the supplied
 * scope. Closing/cancelling a client terminates all sessions it still owns.
 */
public interface ProcessClient : CoroutineScope, AutoCloseable {
    /**
     * Starts [command] without inserting a shell between the caller and child.
     * The returned session and its raw streams remain owned by this client.
     * Child-side executable/cwd rejection can be reported as exit status 127
     * on POSIX rather than as a synchronous startup failure.
     *
     * @throws ProcessException when the client is closed or platform process
     * creation fails synchronously (including rejected executable startup).
     * @throws kotlinx.coroutines.CancellationException when the caller or owner is cancelled.
     */
    public suspend fun start(command: ProcessCommand): ProcessSession

    /** Cancels this owner and requests termination of all owned sessions. */
    override fun close()
}
