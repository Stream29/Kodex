package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Strongly typed command or process input awaiting a result. */
@Serializable
public sealed interface PendingCommandExecutionAction {
    /** Starts a shell command through `exec_command`. */
    @Serializable
    @SerialName("exec_command")
    public data class ExecCommand(
        public val command: String,
        public val workdir: String? = null,
        public val shell: String? = null,
        public val tty: Boolean = false,
        @SerialName("yield_time_ms")
        public val yieldTimeMillis: Long,
        @SerialName("max_output_tokens")
        public val maxOutputTokens: Long,
    ) : PendingCommandExecutionAction

    /** Writes to or polls a running unified-exec session. */
    @Serializable
    @SerialName("write_stdin")
    public data class WriteStdin(
        @SerialName("session_id")
        public val sessionId: Int,
        public val chars: String = "",
        @SerialName("yield_time_ms")
        public val yieldTimeMillis: Long,
        @SerialName("max_output_tokens")
        public val maxOutputTokens: Long,
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
