package io.github.stream29.codex.lite.hook.contract.compaction

/** Hook port owned by the unified compaction pipeline. */
public interface CompactionHooks {
    public suspend fun onPreCompact(request: CompactionHookRequest): CompactionHookResult

    public suspend fun onPostCompact(request: CompactionHookRequest): CompactionHookResult
}

/** Compaction-hook implementation that always permits compaction. */
public data object NoOpCompactionHooks : CompactionHooks {
    override suspend fun onPreCompact(request: CompactionHookRequest): CompactionHookResult =
        CompactionHookResult.Continue

    override suspend fun onPostCompact(request: CompactionHookRequest): CompactionHookResult =
        CompactionHookResult.Continue
}
