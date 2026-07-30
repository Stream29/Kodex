package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanOpenAiEvent
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Completed clean event that can be appended to stable history.
 *
 * Stable history follows tool-result persistence order. It does not retain
 * pending call identities or depend on the unstable clean-model package.
 */
@Serializable
public sealed interface StableCleanEvent : CleanOpenAiEvent {
    /** Stable user content; the OpenAI role is fixed during projection. */
    @Serializable
    @SerialName("user_message")
    public data class UserMessage(
        public val content: List<ContentItem>,
    ) : Steerable {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = content,
                ),
            )
    }

    /**
     * Stable assistant content plus provider metadata needed for projection.
     */
    @Serializable
    @SerialName("assistant_message")
    public data class AssistantMessage(
        public val content: List<ContentItem>,
        public val id: ResponseItemId? = null,
        public val phase: MessagePhase? = null,
    ) : Steerable {
        public val text: String
            get() = content.mapNotNull { part ->
                when (part) {
                    is ContentItem.InputText -> part.text
                    is ContentItem.OutputText -> part.text
                    is ContentItem.InputImage -> null
                }
            }.joinToString(separator = "")

        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(
                ResponseItem.Message(
                    id = id,
                    role = MessageRole.Assistant,
                    content = content,
                    phase = phase,
                ),
            )
    }

    /** Stable developer content; the OpenAI role is fixed during projection. */
    @Serializable
    @SerialName("developer_message")
    public data class DeveloperMessage(
        public val content: List<ContentItem>,
    ) : Steerable {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(
                ResponseItem.Message(
                    role = MessageRole.Developer,
                    content = content,
                ),
            )
    }

    /** Stable inter-Agent delivery fields. */
    @Serializable
    @SerialName("agent_message")
    public data class AgentMessage(
        public val author: String,
        public val recipient: String,
        public val content: List<AgentMessageInputContent>,
    ) : Steerable {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(
                ResponseItem.AgentMessage(
                    author = author,
                    recipient = recipient,
                    content = content,
                ),
            )
    }

    /** Stable reasoning backed by the provider-facing OpenAI item. */
    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        public val item: ResponseItem.Reasoning,
    ) : StableCleanEvent {
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
     *
     * [invocation] retains the irreducible protocol-specific input without
     * retaining a duplicate raw call or output item.
     */
    @Serializable
    @SerialName("invalid_tool_call")
    public data class InvalidToolCall(
        @SerialName("call_id")
        public val callId: String,
        @SerialName("item_id")
        public val itemId: ResponseItemId? = null,
        public val invocation: InvalidToolInvocation,
        public val message: String,
    ) : CompletedTool {
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
    public data class ServerToolSearch(
        public val call: ResponseItem.ServerToolSearchCall,
        public val output: ResponseItem.ServerToolSearchOutput,
    ) : CompletedTool {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(call, output)
    }

    /** Completed hosted web search emitted as one self-contained history item. */
    @Serializable
    @SerialName("hosted_web_search")
    public data class WebSearchCall(
        public val item: ResponseItem.WebSearchCall,
    ) : CompletedTool {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(item)
    }

    /** Completed hosted image generation emitted as one self-contained history item. */
    @Serializable
    @SerialName("hosted_image_generation")
    public data class ImageGenerationCall(
        public val item: ResponseItem.ImageGenerationCall,
    ) : CompletedTool {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            listOf(item)
    }

    /**
     * Empty stable marker for a compaction boundary.
     *
     * The compaction checkpoint owns the replacement prefix and provider-facing
     * compaction data exactly once.
     */
    @Serializable
    @SerialName("context_compaction")
    public data object ContextCompaction : StableCleanEvent {
        override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
            emptyList()
    }

    /**
     * Completed clean event produced by a tool handler.
     *
     * This narrower union prevents tool execution from publishing messages,
     * reasoning, or other non-tool stable events.
     */
    @Serializable
    public sealed interface CompletedTool : StableCleanEvent

    /**
     * Clean input that may be delivered into an active logical turn.
     *
     * This deliberately excludes reasoning, tool events, and compaction
     * markers: those are persisted protocol history, not external steer input.
     */
    @Serializable
    public sealed interface Steerable : StableCleanEvent
}
