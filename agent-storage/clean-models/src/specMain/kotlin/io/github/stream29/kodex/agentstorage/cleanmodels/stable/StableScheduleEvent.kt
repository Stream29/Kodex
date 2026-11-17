package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A fired schedule steered into the Agent.
 *
 * Kept distinct from [StableUserMessage] so UIs can render the trigger; the model sees it as a User message.
 * The trigger persists independently of the schedule definition and is retained across compaction.
 */
@Serializable
@SerialName("event_schedule")
public data class StableScheduleEvent(
    @SerialName("schedule_id")
    public val scheduleId: Long,
    public val description: String,
) : CompactionRetainedItem, AgentCommit.Steerable {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(ContentItem.InputText("schedule #$scheduleId fired: $description")),
            ),
        )
}
