package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableJsonOutput
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableTextOutput
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a command execution or process interaction.
 *
 * [action] distinguishes the local unified-exec functions without retaining
 * their raw JSON arguments.
 */
@Serializable
@SerialName("command_execution_tool_event")
public data class StableCommandExecutionToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val action: StableCommandExecutionAction,
    public val result: StableCommandExecutionResult,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            action.toFunctionCall(callId, itemId),
            result.toFunctionOutput(callId),
        )
}

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

}

/** Completed local command outcome. */
@Serializable
public sealed interface StableCommandExecutionResult {
    /** Output returned by `exec_command` or `write_stdin`. */
    @Serializable
    @SerialName("output")
    public data class Output(
        public val value: UnifiedExecOutput,
    ) : StableCommandExecutionResult

    /** Command execution failed before a structured output was produced. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableCommandExecutionResult
}

private fun StableCommandExecutionAction.toFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
): ResponseItem.FunctionCall =
    when (this) {
        is StableCommandExecutionAction.ExecCommand ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "exec_command",
                serializer = ExecCommandArguments.serializer(),
                arguments = arguments,
            )

        is StableCommandExecutionAction.WriteStdin ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "write_stdin",
                serializer = WriteStdinArguments.serializer(),
                arguments = arguments,
            )
    }

private fun StableCommandExecutionResult.toFunctionOutput(
    callId: String,
): ResponseItem.FunctionCallOutput =
    when (this) {
        is StableCommandExecutionResult.Output ->
            stableJsonOutput(
                callId = callId,
                serializer = UnifiedExecOutput.serializer(),
                result = value,
                success = true,
            )

        is StableCommandExecutionResult.Failure ->
            stableTextOutput(
                callId = callId,
                text = message,
                success = false,
            )
    }
