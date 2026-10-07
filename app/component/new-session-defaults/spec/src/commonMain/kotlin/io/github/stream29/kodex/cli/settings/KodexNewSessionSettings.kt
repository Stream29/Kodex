package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.serialization.Serializable

/** Defaults used to construct a new thread's first settings snapshot. */
@Serializable
public data class KodexNewSessionSettings(
    public val model: OpenAiModelId = OpenAiModelId("gpt-5.6-sol"),
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    public val serviceTier: ServiceTier = ServiceTier.Default,
    public val requestUserInputMode: RequestUserInputMode = RequestUserInputMode.AskUser,
)
