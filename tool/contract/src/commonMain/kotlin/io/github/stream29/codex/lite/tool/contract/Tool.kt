package io.github.stream29.codex.lite.tool.contract

import io.github.stream29.codex.lite.openai.ToolSpec

/**
 * Non-generic executable tool contract used by the agent loop.
 *
 * Tool implementations may use typed DTOs internally. The public boundary uses
 * OpenAI protocol DTOs so agent-loop state can persist calls and results
 * without depending on a tool module's private business model. Implementations
 * own their close behavior because handlers may hold resources.
 */
public interface Tool : AutoCloseable {
    public val spec: ToolSpec

    public suspend fun handle(payload: ToolCallPayload): ToolCallResult
}
