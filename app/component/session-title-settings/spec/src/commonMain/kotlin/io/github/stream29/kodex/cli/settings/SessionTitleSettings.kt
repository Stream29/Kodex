package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.serialization.Serializable

/**
 * Controls the one-shot title request for a newly materialized root session.
 *
 * @property enabled Whether the first accepted text may start title generation.
 * @property model Nullable because callers may use the title generator's
 * compiled default; `null` selects that default model.
 * @property reasoningEffort Reasoning effort sent with the title request.
 */
@Serializable
public data class SessionTitleSettings(
    public val enabled: Boolean = true,
    public val model: OpenAiModelId? = null,
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Low,
)
