package io.github.stream29.kodex.utils.shellclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.io.files.Path

/**
 * Command evaluated by an explicitly selected host shell.
 *
 * Host assembly selects [shell] before constructing the command.
 *
 * @throws IllegalArgumentException when environment keys are not portable shell
 * identifiers or environment values contain NUL.
 */
public data class ShellProcessCommand(
    public val command: String,
    /**
     * Always explicit within the process layer. [Path](`.`) means the child
     * inherits the host process working directory.
     */
    public val workingDirectory: Path = Path("."),
    /** Platform shell selected before this command reaches a shell client. */
    public val shell: Shell,
    /** Whether a shell that supports it should use its login initialization behavior. */
    public val login: Boolean = false,
    /** Whether to attach the command to a pseudoterminal instead of ordinary pipes. */
    public val tty: Boolean = false,
    /** Environment variable overrides applied to the child process before its shell starts. */
    public val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(environment.keys.all(EnvironmentVariableName::matches)) {
            "Environment variable names must use portable shell identifier syntax."
        }
        require(environment.values.none { value -> '\u0000' in value }) {
            "Environment variable values must not contain NUL."
        }
    }
}

private val EnvironmentVariableName: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * A local child process with explicit input, output, and lifecycle ownership.
 *
 * [scope] is a child of the [ShellClient] that created it. Cancelling that
 * scope aborts the session. [close] instead requests termination of the
 * child-process tree while preserving the platform observer until it reports
 * the resulting [exitCode] or the session fails. [SendChannel.send] on [stdin] completes after the
 * corresponding platform write settles.
 */
public interface ProcessSession : AutoCloseable {
    /** Scope that owns this session's process and I/O resources. */
    public val scope: CoroutineScope

    /**
     * Ordered standard input for the child process. Sending acknowledges the
     * platform write, not merely enqueueing. Closing input requests EOF on pipes;
     * it does not close a PTY master (terminal EOF is explicit input).
     *
     * `send` throws `ClosedSendChannelException` after normal input closure;
     * failed or aborted input propagates its failure or cancellation instead.
     * `onSend` is unsupported and throws `UnsupportedOperationException`.
     * Owner cancellation aborts pending sends with cancellation.
     *
     * @throws kotlinx.coroutines.channels.ClosedSendChannelException when sending after normal input closure.
     * @throws UnsupportedOperationException when accessing `onSend`.
     * @throws kotlinx.io.IOException when a platform input write fails.
     * @throws ProcessException when the session reports a platform input failure.
     * @throws kotlinx.coroutines.CancellationException when the owner aborts input.
     */
    public val stdin: SendChannel<String>

    /** Destructive, merged standard output and standard error buffer. */
    public val stdout: StdoutBuffer

    /**
     * Destructive standard-output-only buffer for pipe sessions.
     * PTY sessions expose their terminal stream here because a pseudoterminal
     * does not preserve the stdout/stderr distinction.
     */
    public val standardOutput: StdoutBuffer

    /**
     * Destructive standard-error-only buffer for pipe sessions.
     * PTY sessions leave this buffer empty because a pseudoterminal exposes one
     * combined terminal stream.
     */
    public val standardError: StdoutBuffer

    /**
     * Completes with the final child-process exit code, including after
     * [close] requests termination, unless a session I/O or observer failure
     * completes it exceptionally first. An input/output failure can win even
     * when the platform exit status is known or could subsequently be observed.
     * Cancelling [scope] before completion also fails this deferred.
     *
     * Awaiting propagates the original platform/session failure; the exception
     * families below are not an exhaustive list of platform failures.
     *
     * @throws ProcessException when a process observation or session I/O operation fails.
     * @throws kotlinx.io.IOException when a platform I/O failure is forwarded.
     * @throws kotlinx.coroutines.CancellationException when the session or awaiting caller is cancelled.
     */
    public val exitCode: Deferred<Int>

    /**
     * Requests termination of the child process tree and returns without
     * waiting for it to exit. The request may take time to settle; await
     * [exitCode] for the resulting platform exit code or session failure.
     * Joining [scope]'s Job waits for owned cleanup, not just exit observation.
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
