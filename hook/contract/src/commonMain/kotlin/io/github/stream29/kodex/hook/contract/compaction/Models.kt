package io.github.stream29.kodex.hook.contract.compaction

import io.github.stream29.kodex.hook.contract.HookTurnContext

/** Trigger exposed to PreCompact and PostCompact commands. */
public enum class HookCompactionTrigger(public val wireName: String) {
    Manual("manual"),
    Auto("auto"),
}

/** Input shared by one compaction operation's pre and post hooks. */
public data class CompactionHookRequest(
    public val context: HookTurnContext,
    public val trigger: HookCompactionTrigger,
)
