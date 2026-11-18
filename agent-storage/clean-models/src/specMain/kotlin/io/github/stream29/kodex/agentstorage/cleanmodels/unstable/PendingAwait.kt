package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.agentstorage.cleanmodels.StoredAwaitEventArgumentsSerializer
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** `await_event` call and its execution-only deadline, anchored when the call first lands. */
@Serializable
@SerialName("await_event")
public data class PendingAwait(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    @Serializable(with = StoredAwaitEventArgumentsSerializer::class)
    public val arguments: AwaitEventArguments,
    @SerialName("timeout_at")
    public val timeoutAt: Instant? = null,
    @SerialName("armed_for_agent_id")
    public val armedForAgentId: Int? = null,
) : PendingToolCall {
    init {
        require((arguments.timeoutDelay != null) == (timeoutAt != null)) {
            "Pending await_event with timeout must carry its original timeout_at."
        }
        require(armedForAgentId == null || (armedForAgentId >= 0 && timeoutAt != null)) {
            "Only pending await_event deadlines can be armed."
        }
    }

    override val toolName: String = "await_event"
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "await_event",
                serializer = AwaitEventArguments.serializer(),
                arguments = arguments,
            ),
        )
}
