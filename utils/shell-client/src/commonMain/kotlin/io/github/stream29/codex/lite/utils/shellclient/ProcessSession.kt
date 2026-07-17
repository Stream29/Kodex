package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.io.files.Path

/** Command evaluated by an explicitly selected host shell. */
public data class ShellProcessCommand(
    public val command: String,
    /**
     * Always explicit within the process layer. [Path](`.`) means the child
     * inherits the host process working directory.
     */
    public val workingDirectory: Path = Path("."),
    /** Platform shell selected before this command reaches a shell client. */
    public val shell: Shell = Shell.default,
    /** Whether a shell that supports it should use its login initialization behavior. */
    public val login: Boolean = false,
    /** Whether to attach the command to a pseudoterminal instead of ordinary pipes. */
    public val tty: Boolean = false,
)

/** Initial terminal width used by every PTY backend. */
internal const val DefaultPtyColumns: Int = 80

/** Initial terminal height used by every PTY backend. */
internal const val DefaultPtyRows: Int = 24

/**
 * A local child process with explicit input, output, and lifecycle ownership.
 *
 * [scope] is a child of the [ShellClient] that created it. Cancelling that
 * scope aborts the session. [close] instead requests termination of the
 * child-process tree while preserving the platform observer until it reports
 * the resulting [exitCode]. [SendChannel.send] on [stdin] completes after the
 * corresponding platform write settles.
 */
public interface ProcessSession : AutoCloseable {
    /** Scope that owns this session's process and I/O resources. */
    public val scope: CoroutineScope

    /** Ordered standard input for the child process. */
    public val stdin: SendChannel<String>

    /** Destructive, merged standard output and standard error buffer. */
    public val stdout: StdoutBuffer

    /**
     * Completes with the final child-process exit code, including after
     * [close] requests termination. It fails only when the exit status cannot
     * be observed, such as after an external cancellation of [scope].
     */
    public val exitCode: Deferred<Int>

    /**
     * Requests termination of the child process tree and returns without
     * waiting for it to exit. The request may take time to settle; await
     * [exitCode] for the resulting platform exit code.
     */
    override fun close()
}

/** Failure raised by the local process boundary. */
public class ProcessException(
    message: String,
    /**
     * Nullable because a process operation can fail before a platform API
     * produces a lower-level throwable; `null` means no cause is available.
     */
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
