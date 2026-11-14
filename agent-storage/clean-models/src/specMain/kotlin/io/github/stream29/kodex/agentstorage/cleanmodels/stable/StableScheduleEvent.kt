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
 * The schedule itself lives in the `schedules` table. Not a [CompactionRetainedItem]: once acted on, the
 * trigger is covered by the compaction summary.
 */
@Serializable
@SerialName("event_schedule")
public data class StableScheduleEvent(
    @SerialName("schedule_id")
    public val scheduleId: Long,
    public val description: String,
) : StableIndexEvent.Steerable {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(ContentItem.InputText("schedule #$scheduleId fired: $description")),
            ),
        )
}
