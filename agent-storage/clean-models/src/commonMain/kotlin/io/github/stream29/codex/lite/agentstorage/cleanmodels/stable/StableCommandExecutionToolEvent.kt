package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
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
) : StableCleanEvent.CompletedTool

/** Strongly typed command or process interaction. */
@Serializable
public sealed interface StableCommandExecutionAction {
    /** Starts a shell command through `exec_command`. */
    @Serializable
    @SerialName("exec_command")
    public data class ExecCommand(
        public val arguments: ExecCommandArguments,
    ) : StableCommandExecutionAction

    /** Writes to or polls a running unified-exec session. */
    @Serializable
    @SerialName("write_stdin")
    public data class WriteStdin(
        public val arguments: WriteStdinArguments,
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
        public val value: UnifiedExecOutput,
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
