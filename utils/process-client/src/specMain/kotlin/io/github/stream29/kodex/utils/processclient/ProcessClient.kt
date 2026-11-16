package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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

    override fun close()

    public suspend fun closeAndJoin()
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
    public suspend fun start(command: ProcessCommand): ProcessSession

    /** Cancels this owner and requests termination of all owned sessions. */
    override fun close()
}

/**
 * Creates an independently cancellable direct-process client under this scope.
 * @throws IllegalArgumentException when this scope has no parent Job.
 */
public expect fun CoroutineScope.ProcessClient(): ProcessClient
