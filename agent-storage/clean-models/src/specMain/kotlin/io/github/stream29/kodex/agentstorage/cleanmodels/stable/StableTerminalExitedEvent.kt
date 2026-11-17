package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A watched background terminal exit steered into the Agent.
 *
 * Kept distinct from [StableUserMessage] so UIs can render the terminal completion;
 * the model sees it as a User message. Not a [CompactionRetainedItem]: once acted on,
 * the completion is covered by the compaction summary.
 */
@Serializable
@SerialName("event_terminal_exited")
public data class StableTerminalExitedEvent(
    @SerialName("session_id")
    public val sessionId: Int,
    public val name: String? = null,
    public val command: String,
    @SerialName("exit_code")
    public val exitCode: Int,
) : WorkCommit, AgentCommit.Steerable {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> {
        val label = if (!name.isNullOrBlank()) "terminal #$sessionId ($name)" else "terminal #$sessionId"
        return listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(
                    ContentItem.InputText("$label exited with code $exitCode: $command"),
                ),
            ),
        )
    }
}
