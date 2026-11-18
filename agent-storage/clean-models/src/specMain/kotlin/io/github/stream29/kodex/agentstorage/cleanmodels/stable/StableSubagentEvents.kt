package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parent History Index anchor. Creation alone does not start or steer a parent turn. */
@Serializable
@SerialName("event_subagent_created")
public data class StableSubagentCreatedEvent(
    @SerialName("child_agent_id") public val childAgentId: Int,
) : CompactionRetainedItem, AgentCommit.ConversationMessage {
    init { require(childAgentId >= 0) }

    public val notice: String get() = "Subagent #$childAgentId created"

    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(ResponseItem.Message(role = MessageRole.Developer, content = listOf(ContentItem.InputText(notice))))
}

/** Notification for the direct parent, not a result or an acceptance decision. */
@Serializable
@SerialName("event_subagent_completed")
public data class StableSubagentCompletedEvent(
    @SerialName("child_agent_id") public val childAgentId: Int,
) : WorkCommit, AgentCommit.Steerable {
    init { require(childAgentId >= 0) }

    public val notice: String get() = "Subagent #$childAgentId completed"

    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(ResponseItem.Message(role = MessageRole.User, content = listOf(ContentItem.InputText(notice))))
}
