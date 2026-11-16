package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort

/**
 * Backend-local port for generating a title from accepted user text.
 */
internal fun interface SessionTitleGenerator {
    suspend fun generateTitle(
        userText: String,
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
    ): SessionTitleGenerationResult
}

/** Local outcome of one attempt; never a title-generation state or wire DTO. */
internal sealed interface SessionTitleGenerationResult {
    /** A normalized title ready for conditional persistence, not yet committed. */
    data class Generated(val title: String) : SessionTitleGenerationResult

    /** An attempt that did not produce a usable title; [reason] is diagnostic data. */
    data class Rejected(val reason: String) : SessionTitleGenerationResult
}
