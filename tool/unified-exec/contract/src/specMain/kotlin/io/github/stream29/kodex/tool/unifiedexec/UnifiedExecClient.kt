package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.utils.shellclient.ShellSettings
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * Session-scoped client backing `exec_command` and background terminal controls.
 *
 * Closing the client cancels its owned process sessions. Commands use the
 * selected shell's login initialization; model calls cannot disable it. A
 * command without an explicit shell captures the current [ShellSettings.shell]
 * when its process starts. A still-running command remains registered in
 * [activeSessions] and returns its session ID; completed background sessions
 * remain addressable in [activeSessions] for [UnifiedExecCompletedSessionRetentionMillis]
 * after final process output and [UnifiedExecProcessSession.exitCode] settle.
 */
public interface UnifiedExecClient : AutoCloseable {
    /**
     * Addressable terminal sessions keyed by their public session identifier,
     * including running sessions and completed sessions within their retention window.
     *
     * This is a read-only view of the registry that owns session membership,
     * not a count of processes still running; inspect whether each
     * [UnifiedExecProcessSession.exitCode] is non-null to distinguish completed sessions.
     */
    public val activeSessions: StateFlow<Map<Int, UnifiedExecProcessSession>>

    /**
     * Starts a command and yields the output available after the requested
     * wait. A still-running process remains registered and returns a session
     * ID; a completed process returns its exit code and final output without
     * a session ID. Output may be truncated to the requested token budget.
     *
     * @throws UnifiedExecToolException if arguments are invalid, the client
     * cannot register a session, or a local process operation fails.
     */
    public suspend fun execCommand(arguments: ExecCommandArguments): UnifiedExecOutput

    /** Requests termination of owned process sessions and releases the client. */
    override fun close()
}

/**
 * Observable control surface for one unified-exec process session.
 *
 * [exitCode] is the single completion state source (`null` while running, non-null once
 * final IO has drained and process exit is observed). Consumers can inspect metadata,
 * non-destructively read output slices via [readOutput], write input via [writeStdin],
 * or request process-tree termination via [close] while keeping the session registered
 * during its post-exit retention window.
 */
public interface UnifiedExecProcessSession : AutoCloseable {
    /** Positive identifier used to address this session. */
    public val sessionId: Int

    /** Identifies this registration when the public numeric ID is reused. Not a process ID. */
    public val registrationNonce: String

    /** Resolved working directory in which this session was started. */
    public val cwd: Path

    /** Original command arguments, including the nullable shell request. */
    public val arguments: ExecCommandArguments

    /**
     * Single source of truth for process completion: `null` while running, or the observed
     * process exit code after final process output has drained; the session remains in
     * [UnifiedExecClient.activeSessions] until its retention window expires.
     */
    public val exitCode: StateFlow<Int?>

    /** Non-destructively reads output starting at byte [offset] up to [limit] bytes. */
    public suspend fun readOutput(offset: ULong = 0uL, limit: UInt = UInt.MAX_VALUE): StdoutSlice

    /** Writes [chars] to the session's standard input, optionally closing input afterwards when [closeInput] is `true`. */
    public suspend fun writeStdin(chars: String, closeInput: Boolean = false)

    /** Requests process-tree termination without immediately removing the session from the client. */
    override fun close()
}
