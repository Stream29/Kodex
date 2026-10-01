package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-scoped client shared by the `exec_command` and `write_stdin` tools.
 *
 * Closing the client cancels its active process sessions. Commands use the
 * selected shell's login initialization; model calls cannot disable it. A
 * command without an explicit shell captures the current [ShellSettings.shell]
 * when its process starts. A returned session ID can be used for later input
 * or polling until the final output is read.
 */
public interface UnifiedExecClient : AutoCloseable {
    /**
     * Sessions that can still be read through `write_stdin`, keyed by their
     * public session identifier. A completed session can remain here until
     * its final output is read.
     *
     * This is a read-only view of the registry that owns session membership,
     * not a count of processes still running; inspect each
     * [UnifiedExecProcessSession.completed] state to distinguish
     * completed-but-unread sessions.
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

    /**
     * Sends [WriteStdinArguments.chars] or, when empty, polls an existing
     * session without creating a new process. Reading a completed session's
     * final output removes it from [activeSessions]. A session whose
     * [UnifiedExecProcessSession.close] requested termination can still be
     * read after its exit becomes observable.
     *
     * @throws UnifiedExecToolException if arguments are invalid, the session
     * is unknown or unavailable, or a local process operation fails.
     */
    public suspend fun writeStdin(arguments: WriteStdinArguments): UnifiedExecOutput

    /** Requests termination of owned process sessions and releases the client. */
    override fun close()
}

/**
 * Observable control surface for one active unified-exec process session.
 *
 * Consumers can use the original command and [completed] state for
 * presentation. [close] requests process-tree termination but leaves the
 * session registered for a final `write_stdin` read after process exit is
 * observed.
 */
public interface UnifiedExecProcessSession : AutoCloseable {
    /** Positive identifier used to address this session through `write_stdin`. */
    public val sessionId: Int

    /** Original command arguments, including the nullable shell request. */
    public val arguments: ExecCommandArguments

    /**
     * Becomes `true` after the process exit code is observed; it does not
     * indicate that the final output has been read or the session removed.
     */
    public val completed: StateFlow<Boolean>

    /** Requests process-tree termination without removing the session from the client. */
    override fun close()
}
