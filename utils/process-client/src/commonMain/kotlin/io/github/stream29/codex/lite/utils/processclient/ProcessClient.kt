package io.github.stream29.codex.lite.utils.processclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newCoroutineContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext

/**
 * Stateful owner for direct child processes using ordinary byte pipes.
 *
 * A client created by [CoroutineScope.ProcessClient] is a child of that scope.
 * Closing it terminates every process session that it still owns.
 */
public expect class ProcessClient internal constructor(
    scope: CoroutineScope,
) : CoroutineScope, AutoCloseable {
    override val coroutineContext: CoroutineContext

    /** Starts [command] without inserting a shell between the caller and child process. */
    public suspend fun start(command: ProcessCommand): ProcessSession

    override fun close()
}

/** Creates an independently cancellable direct-process client under this scope. */
@OptIn(ExperimentalCoroutinesApi::class)
public fun CoroutineScope.ProcessClient(): ProcessClient {
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "ProcessClient requires an owning CoroutineScope with a Job."
    }
    return ProcessClient(
        CoroutineScope(newCoroutineContext(SupervisorJob(parentJob))),
    )
}

/** One direct executable invocation. */
public data class ProcessCommand(
    public val executable: String,
    public val arguments: List<String> = emptyList(),
    /** `Path(".")` means that the child inherits the host process working directory. */
    public val workingDirectory: Path = Path("."),
    /** Environment variables overlaid on the inherited host environment. */
    public val environment: Map<String, String> = emptyMap(),
)

/**
 * A direct child process with raw standard streams.
 *
 * Stream reads and writes may block. Callers choose the dispatcher on which
 * they consume the streams. [close] requests termination of the process tree;
 * the resulting status is eventually published through [exitCode].
 */
public interface ProcessSession : AutoCloseable {
    public val stdin: RawSink
    public val stdout: RawSource
    public val stderr: RawSource
    public val exitCode: Deferred<Int>

    override fun close()
}

/** Failure raised by the direct process boundary. */
public class ProcessException(
    message: String,
    /**
     * Nullable because a process operation can fail before a platform API
     * produces a lower-level throwable; `null` means no cause is available.
     */
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Process client is closed.")
}

internal fun CoroutineScope.lazyProcessCancellationGuard(
    close: () -> Unit,
): Job = launch(start = CoroutineStart.LAZY) {
    try {
        awaitCancellation()
    } finally {
        close()
    }
}
