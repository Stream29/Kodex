package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Strongly typed command or process input awaiting a result. */
@Serializable
public sealed interface PendingCommandExecutionAction {
    /** Starts a shell command through `exec_command`. */
    @Serializable
    @SerialName("exec_command")
    public data class ExecCommand(
        public val arguments: ExecCommandArguments,
    ) : PendingCommandExecutionAction

    /** Writes to or polls a running unified-exec session. */
    @Serializable
    @SerialName("write_stdin")
    public data class WriteStdin(
        public val arguments: WriteStdinArguments,
    ) : PendingCommandExecutionAction

    /** Command supplied through a hosted `local_shell_call`. */
    @Serializable
    @SerialName("local_shell")
    public data class LocalShell(
        public val command: List<String>,
        @SerialName("timeout_ms")
        public val timeoutMillis: Long? = null,
        @SerialName("working_directory")
        public val workingDirectory: String? = null,
        public val environment: Map<String, String>? = null,
        public val user: String? = null,
    ) : PendingCommandExecutionAction
}
