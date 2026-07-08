package io.github.stream29.codex.lite.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ModelsResponse(
    public val models: List<ModelInfo> = emptyList(),
)

/**
 * Minimal model metadata returned by the Codex backend `/models` endpoint.
 *
 * @property slug Stable backend model identifier.
 * @property displayName Human-readable model name.
 */
@Serializable
public data class ModelInfo(
    public val slug: OpenAiModelId,
    @SerialName("display_name")
    public val displayName: String,
)
