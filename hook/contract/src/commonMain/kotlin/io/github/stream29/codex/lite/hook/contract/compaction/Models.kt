package io.github.stream29.codex.lite.hook.contract.compaction

import io.github.stream29.codex.lite.hook.contract.HookTurnContext

/** Trigger visible to PreCompact and PostCompact matchers. */
public enum class HookCompactionTrigger(public val wireName: String) {
    Manual("manual"),
    Auto("auto"),
}

/** Input shared by one compaction operation's pre and post hooks. */
public data class CompactionHookRequest(
    public val context: HookTurnContext,
    public val trigger: HookCompactionTrigger,
)
