package io.github.stream29.kodex.hook.contract.tool

import io.github.stream29.kodex.hook.contract.HookTurnContext
import kotlinx.serialization.json.JsonElement

/** Stable hook-facing view of one executable tool call. */
public data class HookToolInvocation(
    public val context: HookTurnContext,
    public val toolName: String,
    public val toolUseId: String,
    public val input: JsonElement,
)

/** Result of authorizing a tool call through configured PreToolUse hooks. */
public sealed interface PreToolUseResult {
    /** The original tool call may proceed unchanged. */
    public data object Continue : PreToolUseResult

    /** @property reason Model-facing explanation for the blocked call. */
    public data class Block(
        public val reason: String = "PreToolUse hook blocked this tool call.",
    ) : PreToolUseResult
}

/** Input exposed after a tool has reached its hook-defined terminal state. */
public data class PostToolUseRequest(
    public val invocation: HookToolInvocation,
    public val response: JsonElement,
)
