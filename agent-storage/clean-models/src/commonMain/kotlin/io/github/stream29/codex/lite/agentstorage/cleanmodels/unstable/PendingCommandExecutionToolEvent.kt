package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local command or process interaction waiting for execution. */
@Serializable
@SerialName("command_execution")
public data class PendingCommandExecutionToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val action: PendingCommandExecutionAction,
) : PendingToolEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(action.toFunctionCall(callId, itemId))
}

private fun PendingCommandExecutionAction.toFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
): ResponseItem.FunctionCall =
    when (this) {
        is PendingCommandExecutionAction.ExecCommand ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "exec_command",
                serializer = ExecCommandArguments.serializer(),
                arguments = arguments,
            )

        is PendingCommandExecutionAction.WriteStdin ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "write_stdin",
                serializer = WriteStdinArguments.serializer(),
                arguments = arguments,
            )
    }
