package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.coroutines.Deferred
import kotlinx.io.files.Path

/** One direct executable invocation without an intermediate shell. */
public data class ProcessCommand(
    public val executable: String,
    public val arguments: List<String> = emptyList(),
    public val workingDirectory: Path = Path("."),
    public val environment: Map<String, String> = emptyMap(),
)

/**
 * Direct child process with independently owned raw standard streams.
 *
 * Implementations must make stream operations suspendable and must publish the
 * final process status through [exitCode].
 */
public interface ProcessSession : AutoCloseable {
    public val stdin: CoroutineRawSink
    public val stdout: CoroutineRawSource
    public val stderr: CoroutineRawSource
    public val exitCode: Deferred<Int>

    override fun close()
}

/** Failure raised by a direct process implementation. */
public open class ProcessException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Replaceable process-launching boundary used by shell and tool implementations. */
public interface ProcessClientSpec : AutoCloseable {
    /** Starts [command] and transfers ownership of its streams to the caller. */
    public suspend fun start(command: ProcessCommand): ProcessSession

    override fun close()
}
