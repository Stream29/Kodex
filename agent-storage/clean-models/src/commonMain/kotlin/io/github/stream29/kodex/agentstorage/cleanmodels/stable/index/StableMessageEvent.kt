package io.github.stream29.kodex.agentstorage.cleanmodels.stable.index

import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable user content; the OpenAI role is fixed during projection. */
@Serializable
@SerialName("user_message")
public data class StableUserMessage(
    public val content: List<ContentItem>,
) : StableIndexEvent.Steerable, CompactionRetainedItem {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = content,
            ),
        )
}

/** Stable assistant content plus provider metadata needed for projection. */
@Serializable
@SerialName("assistant_message")
public data class StableAssistantMessage(
    public val content: List<ContentItem>,
    public val id: ResponseItemId? = null,
    public val phase: MessagePhase? = null,
) : StableIndexEvent.Steerable {
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
public data class StableDeveloperMessage(
    public val content: List<ContentItem>,
) : StableIndexEvent.Steerable {
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
public data class StableAgentMessage(
    public val author: String,
    public val recipient: String,
    public val content: List<AgentMessageInputContent>,
) : StableIndexEvent.Steerable {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.AgentMessage(
                author = author,
                recipient = recipient,
                content = content,
            ),
        )
}
