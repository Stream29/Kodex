package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable reasoning backed by the provider-facing OpenAI item. */
@Serializable
@SerialName("reasoning")
public data class StableReasoning(
    public val item: ResponseItem.Reasoning,
) : WorkCommit {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
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
) : WorkCommit, AgentCommit.CompletedTool {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
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
) : WorkCommit, AgentCommit.CompletedTool {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(call, output)
}

/** Completed hosted web search emitted as one self-contained history item. */
@Serializable
@SerialName("hosted_web_search")
public data class StableWebSearchCall(
    public val item: ResponseItem.WebSearchCall,
) : WorkCommit, AgentCommit.CompletedTool {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(item)
}

/** Completed hosted image generation emitted as one history item. */
@Serializable
@SerialName("hosted_image_generation")
public data class StableImageGenerationCall(
    public val item: ResponseItem.ImageGenerationCall,
) : WorkCommit, AgentCommit.CompletedTool {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(item)
}

/**
 * Self-contained remote-compaction checkpoint at a context-window boundary.
 *
 * Bundles the ordered [retainedItems] prefix (within the 64,000-token retention
 * budget, including answered [StableAskUserEvent] items) together with the encrypted
 * compaction summary. Replaying model input starts directly from this commit
 * without scanning earlier indices.
 */
@Serializable
@SerialName("context_compaction")
public data class StableContextCompaction(
    public val id: ResponseItemId? = null,
    @SerialName("encrypted_content")
    public val encryptedContent: String,
    @SerialName("retained_items")
    public val retainedItems: List<CompactionRetainedItem> = emptyList(),
) : IndexCommit {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        buildList {
            for (item: AgentCommit in retainedItems) {
                addAll(item.toResponseHistoryItems())
            }
            add(
                ResponseItem.Compaction(
                    id = id,
                    encryptedContent = encryptedContent,
                ),
            )
        }
}
