package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of an OpenAI `message` item whose role is `user`.
 *
 * OpenAI represents user input as `type = "message"` plus `role = "user"`;
 * user and assistant messages are intentionally modeled separately here because
 * their legal content parts differ.
 *
 * @property content User input content parts in message order.
 */
@Serializable
public data class StableUserMessage(
    public val content: List<StableUserMessageContent>,
)

/**
 * Content part allowed in a stable user message.
 */
@Serializable
public sealed interface StableUserMessageContent {
    /**
     * Text content, projected from OpenAI `input_text`.
     *
     * @property text User-provided text.
     */
    @Serializable
    @SerialName("text")
    public data class Text(
        public val text: String,
    ) : StableUserMessageContent

    /**
     * Image content, projected from OpenAI `input_image`.
     *
     * @property url Image URL or base64 data URL.
     */
    @Serializable
    @SerialName("image")
    public data class Image(
        public val url: String,
    ) : StableUserMessageContent
}
