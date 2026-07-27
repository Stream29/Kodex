package io.github.stream29.codex.lite.hook.contract.tool

/** Hook port owned by actual tool executors. */
public interface ToolHooks {
    public suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult

    /** Observes a successfully completed tool call without changing its result. */
    public suspend fun onPostToolUse(request: PostToolUseRequest)
}

/** Tool-hook implementation that preserves every invocation and result. */
public data object NoOpToolHooks : ToolHooks {
    override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult =
        PreToolUseResult.Continue

    override suspend fun onPostToolUse(request: PostToolUseRequest) {
    }
}
