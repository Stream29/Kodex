package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a command execution or process interaction.
 *
 * [action] distinguishes the local unified-exec functions from hosted shell
 * execution without retaining their raw JSON arguments.
 */
@Serializable
@SerialName("command_execution_tool_event")
public data class StableCommandExecutionToolEvent(
    public val action: StableCommandExecutionAction,
    public val result: StableCommandExecutionResult,
) : StableToolEvent

/** Strongly typed command or process interaction. */
@Serializable
public sealed interface StableCommandExecutionAction {
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
    ) : StableCommandExecutionAction

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
    ) : StableCommandExecutionAction

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
    ) : StableCommandExecutionAction
}

/** Completed or provider-reported command outcome. */
@Serializable
public sealed interface StableCommandExecutionResult {
    /** Output returned by `exec_command` or `write_stdin`. */
    @Serializable
    @SerialName("output")
    public data class Output(
        @SerialName("chunk_id")
        public val chunkId: String,
        @SerialName("wall_time_seconds")
        public val wallTimeSeconds: Double,
        @SerialName("exit_code")
        public val exitCode: Int? = null,
        @SerialName("session_id")
        public val sessionId: Int? = null,
        @SerialName("original_token_count")
        public val originalTokenCount: Long,
        public val output: String,
    ) : StableCommandExecutionResult

    /** Status reported by a hosted shell item without a separate output. */
    @Serializable
    @SerialName("status")
    public data class Status(
        public val status: StableCommandExecutionStatus,
    ) : StableCommandExecutionResult

    /** Command execution failed before a structured output was produced. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableCommandExecutionResult
}

/** Provider-reported lifecycle state for hosted command execution. */
@Serializable
public enum class StableCommandExecutionStatus {
    @SerialName("in_progress")
    InProgress,

    @SerialName("completed")
    Completed,

    @SerialName("incomplete")
    Incomplete,
}
