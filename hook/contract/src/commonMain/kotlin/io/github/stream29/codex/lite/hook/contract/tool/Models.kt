package io.github.stream29.codex.lite.hook.contract.tool

import io.github.stream29.codex.lite.hook.contract.HookTurnContext
import kotlinx.serialization.json.JsonElement

/** Stable hook-facing view of one executable tool call. */
public data class HookToolInvocation(
    public val context: HookTurnContext,
    public val toolName: String,
    public val matcherAliases: List<String> = emptyList(),
    public val toolUseId: String,
    public val input: JsonElement,
)

/** Result of running matching PreToolUse hooks as a configured-order pipeline. */
public sealed interface PreToolUseResult {
    public val additionalContexts: List<String>

    /**
     * @property updatedInput Nullable because the complete pipeline may accept
     * the original invocation; otherwise this is the pipeline's final effective input.
     */
    public data class Continue(
        public val updatedInput: JsonElement? = null,
        override val additionalContexts: List<String> = emptyList(),
    ) : PreToolUseResult

    /**
     * @property reason Nullable because a valid deny result may omit feedback;
     * `null` means the call is blocked without a model-facing reason.
     */
    public data class Block(
        public val reason: String?,
        override val additionalContexts: List<String> = emptyList(),
    ) : PreToolUseResult
}

/** Input exposed after a tool has reached its hook-defined terminal state. */
public data class PostToolUseRequest(
    public val invocation: HookToolInvocation,
    public val response: JsonElement,
)

/** Result of running matching PostToolUse hooks. */
public sealed interface PostToolUseResult {
    public val additionalContexts: List<String>
    public val feedback: String?

    /**
     * @property feedback Nullable because a successful PostToolUse hook need
     * not replace the model-visible tool result; `null` preserves it unchanged.
     */
    public data class Continue(
        override val additionalContexts: List<String> = emptyList(),
        override val feedback: String? = null,
    ) : PostToolUseResult

    public data class Block(
        override val feedback: String,
        override val additionalContexts: List<String> = emptyList(),
    ) : PostToolUseResult
}
