package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.CancellationException

/**
 * Backend-local port for generating a title from accepted user text.
 *
 * This is a model-provider dependency, not an RPC service or a frontend command.
 * Implementations do not own Session state, settings, one-shot eligibility or
 * persistence. The backend runtime owns those concerns and the request lifetime.
 */
public fun interface SessionTitleGenerator {
    /**
     * Runs one attempt without changing Session state.
     *
     * @param userText The first accepted nonblank user text selected by the runtime;
     * implementations may bound the model input without modifying the stored message.
     * @param model The resolved title model, including the configured default when
     * no override exists. This port does not select a model from frontend state.
     * @param reasoningEffort The configured reasoning effort for this attempt.
     * @return [SessionTitleGenerationResult.Generated] for a normalized usable title,
     * or [SessionTitleGenerationResult.Rejected] when the response provides no usable
     * title (including failed, incomplete or empty output). A result is not a write
     * receipt: the runtime must conditionally commit it against current settings and
     * reject output invalidated by a rename, history replacement or owner shutdown.
     * @throws CancellationException When the owning request is cancelled; callers
     * must preserve cancellation rather than convert it into a successful result.
     * @throws Exception When provider access, transport or response processing fails.
     * Optional generation failure must not reverse an already accepted user append.
     */
    public suspend fun generateTitle(
        userText: String,
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
    ): SessionTitleGenerationResult
}

/** Local outcome of one attempt; never a title-generation state or wire DTO. */
public sealed interface SessionTitleGenerationResult {
    /** A normalized title ready for conditional persistence, not yet committed. */
    public data class Generated(public val title: String) : SessionTitleGenerationResult

    /** An attempt that did not produce a usable title; [reason] is diagnostic data. */
    public data class Rejected(public val reason: String) : SessionTitleGenerationResult
}
