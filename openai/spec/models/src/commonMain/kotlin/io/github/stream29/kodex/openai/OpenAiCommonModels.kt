package io.github.stream29.kodex.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Stable OpenAI/Codex model identifier used on request wires and model catalogs.
 */
@JvmInline
@Serializable
public value class OpenAiModelId(public val value: String) {
    init {
        require(value.isNotBlank()) { "OpenAI model id must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * OpenAI service tier selection.
 *
 * [Default] is the request/config sentinel for explicit standard routing. It is
 * not a catalog service tier id.
 *
 * @property requestValue Wire value accepted by the Responses API.
 */
@Serializable
public enum class ServiceTier(public val requestValue: String) {
    @SerialName("default")
    Default("default"),

    @SerialName("priority")
    Fast("priority"),

    @SerialName("flex")
    Flex("flex"),
}
