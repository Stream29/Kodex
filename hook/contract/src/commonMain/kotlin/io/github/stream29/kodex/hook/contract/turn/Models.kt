package io.github.stream29.kodex.hook.contract.turn

import io.github.stream29.kodex.hook.contract.HookTurnContext

/**
 * One model-visible continuation emitted by a Stop hook run.
 *
 * @property hookRunId Composite identity of all handlers participating in the
 * Stop hook run, in configured order.
 */
public data class HookPromptFragment(
    public val text: String,
    public val hookRunId: String,
)

/** Persisted user input inspected before a runtime delegates model execution. */
public data class UserPromptSubmitRequest(
    public val context: HookTurnContext,
    public val prompt: String,
)

/** Result of inspecting a persisted user prompt before model execution. */
public sealed interface UserPromptSubmitResult {
    public val additionalContexts: List<String>

    public data class Continue(
        override val additionalContexts: List<String> = emptyList(),
    ) : UserPromptSubmitResult

    /**
     * @property reason Nullable because `continue:false` may omit a reason;
     * `null` means the turn is stopped without user-facing hook feedback.
     */
    public data class Stop(
        public val reason: String?,
        override val additionalContexts: List<String> = emptyList(),
    ) : UserPromptSubmitResult
}

/**
 * Input inspected at a stop candidate within one outermost turn.
 *
 * A candidate can be a natural assistant completion or a host-visible user
 * input wait that pauses the outer runtime.
 *
 * @property lastAssistantMessage Nullable because a stop candidate can occur
 * without an assistant text message; `null` means no assistant text is
 * available to the hook.
 */
public data class StopRequest(
    public val context: HookTurnContext,
    public val stopHookActive: Boolean,
    public val lastAssistantMessage: String?,
)

/** Result of inspecting the current stop candidate. */
public sealed interface StopResult {
    /** Accepts the current stop candidate. */
    public data object Finish : StopResult

    /** Continues the same turn with model-visible hook feedback. */
    public data class Continue(
        public val fragments: List<HookPromptFragment>,
    ) : StopResult

    /**
     * Stops the turn without requesting another model response.
     *
     * @property reason Nullable because `continue:false` may omit a reason;
     * `null` means no user-facing hook reason was supplied.
     */
    public data class Stop(public val reason: String?) : StopResult
}
