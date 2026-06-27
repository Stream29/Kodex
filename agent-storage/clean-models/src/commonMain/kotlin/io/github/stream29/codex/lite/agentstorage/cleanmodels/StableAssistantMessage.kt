package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.Serializable

/**
 * Stable clean projection of an OpenAI `message` item whose role is `assistant`.
 *
 * Assistant message content is text-only in the stable model. OpenAI normally
 * emits assistant text as `output_text`, while compatibility paths may still
 * surface `input_text`; both are projected into this normalized field.
 *
 * @property text Assistant-visible text after provider-specific normalization.
 */
@Serializable
public data class StableAssistantMessage(
    public val text: String,
)
