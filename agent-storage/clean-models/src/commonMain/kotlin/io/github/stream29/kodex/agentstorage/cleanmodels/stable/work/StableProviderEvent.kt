package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable reasoning backed by the provider-facing OpenAI item. */
@Serializable
@SerialName("reasoning")
public data class StableReasoning(
    public val item: ResponseItem.Reasoning,
) : StableWorkEvent {
    public val display: String
        get() = item.summary.joinToString(separator = "\n") { part ->
            when (part) {
                is ReasoningItemReasoningSummary.SummaryText -> part.text
            }
        }

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(item)
}

/**
 * Tool call rejected before execution because its input could not be parsed.
 */
@Serializable
@SerialName("invalid_tool_call")
public data class StableInvalidToolCall(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val invocation: InvalidToolInvocation,
    public val message: String,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        invocation.toResponseHistoryItems(
            callId = callId,
            itemId = itemId,
            message = message,
        )
}

/** Completed hosted tool search emitted as one call/output pair. */
@Serializable
@SerialName("server_tool_search")
public data class StableServerToolSearch(
    public val call: ResponseItem.ServerToolSearchCall,
    public val output: ResponseItem.ServerToolSearchOutput,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(call, output)
}

/** Completed hosted web search emitted as one self-contained history item. */
@Serializable
@SerialName("hosted_web_search")
public data class StableWebSearchCall(
    public val item: ResponseItem.WebSearchCall,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(item)
}

/** Completed hosted image generation emitted as one history item. */
@Serializable
@SerialName("hosted_image_generation")
public data class StableImageGenerationCall(
    public val item: ResponseItem.ImageGenerationCall,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(item)
}

/** Stable remote-compaction payload at a context-window boundary. */
@Serializable
@SerialName("context_compaction")
public data class StableContextCompaction(
    public val id: ResponseItemId? = null,
    @SerialName("encrypted_content")
    public val encryptedContent: String,
) : StableWorkEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.Compaction(
                id = id,
                encryptedContent = encryptedContent,
            ),
        )
}
