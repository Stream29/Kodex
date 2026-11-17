package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An answered `ask_user` form steered into the Agent.
 *
 * Kept distinct from [StableUserMessage] so UIs can render the form result; the model sees it as
 * a User message, and [CompactionRetainedItem] preserves it across context compactions within the
 * 64,000-token retention budget. Answering an `ask_user` atomically updates [AskUserSnapshot] and
 * queues this event into [SteerSnapshot] at the same storage index.
 */
@Serializable
@SerialName("event_ask_user")
public data class StableAskUserEvent(
    @SerialName("ask_id")
    public val askId: Int,
    public val arguments: RequestUserInputArgs,
    public val response: RequestUserInputResponse,
) : CompactionRetainedItem, AgentCommit.Steerable {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(
                    ContentItem.InputText(
                        "ask_user #$askId answered.\n" +
                            "questions: ${OpenAiJsonCodec.encodeToString(RequestUserInputArgs.serializer(), arguments)}\n" +
                            "answers: ${OpenAiJsonCodec.encodeToString(RequestUserInputResponse.serializer(), response)}",
                    ),
                ),
            ),
        )
}
