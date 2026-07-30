package io.github.stream29.codex.lite.cli.sessiontitle

import io.github.stream29.codex.lite.openai.OpenAiModelId

/** Compiled model used when global settings do not override title generation. */
public val DefaultSessionTitleModel: OpenAiModelId = OpenAiModelId("gpt-5.3-codex-spark")

/** Generates one UI title from the accepted user text that opened a session. */
public fun interface SessionTitleGenerator {
    /** Runs one title-generation attempt without changing session state. */
    public suspend fun generateTitle(
        userText: String,
        model: OpenAiModelId,
    ): SessionTitleGenerationResult
}

/** Result of one title-generation attempt. */
public sealed interface SessionTitleGenerationResult {
    /** A normalized title that is ready for conditional persistence. */
    public data class Generated(public val title: String) : SessionTitleGenerationResult

    /** A completed request that did not produce a usable title. */
    public data class Rejected(public val reason: String) : SessionTitleGenerationResult
}
