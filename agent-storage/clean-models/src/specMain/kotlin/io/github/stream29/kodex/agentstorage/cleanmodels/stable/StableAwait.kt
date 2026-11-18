package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.agentstorage.cleanmodels.StoredAwaitEventArgumentsSerializer
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable clean projection of a completed `await_event` tool call. */
@Serializable
@SerialName("await_event_tool_event")
public data class StableAwait(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    @Serializable(with = StoredAwaitEventArgumentsSerializer::class)
    public val arguments: AwaitEventArguments,
    public val result: StableAwaitEventResult,
) : WorkCommit, AgentCommit.CompletedTool {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "await_event",
                serializer = AwaitEventArguments.serializer(),
                arguments = arguments,
            ),
            when (result) {
                is StableAwaitEventResult.Output ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = AwaitEventOutput.serializer(),
                        result = result.value,
                        success = true,
                    )
                is StableAwaitEventResult.Failure ->
                    stableTextOutput(
                        callId = callId,
                        text = result.message,
                        success = false,
                    )
            },
        )
}

@Serializable
public sealed interface StableAwaitEventResult {
    @Serializable
    @SerialName("output")
    public data class Output(
        public val value: AwaitEventOutput,
    ) : StableAwaitEventResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableAwaitEventResult
}
