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

/** Control result for either side of a compaction operation. */
public sealed interface CompactionHookResult {
    public data object Continue : CompactionHookResult

    /**
     * @property reason Nullable because `continue:false` may omit a reason;
     * `null` means compaction control stopped without hook feedback.
     */
    public data class Stop(public val reason: String?) : CompactionHookResult
}
