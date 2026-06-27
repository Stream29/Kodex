package io.github.stream29.codex.lite.openai

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
 */
@Serializable
public enum class ServiceTier {
    @SerialName("default")
    Default,

    @SerialName("priority")
    Fast,

    @SerialName("flex")
    Flex,
}
