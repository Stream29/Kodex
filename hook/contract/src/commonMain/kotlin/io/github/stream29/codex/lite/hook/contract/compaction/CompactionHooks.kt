package io.github.stream29.codex.lite.hook.contract.compaction

/** Observation-only Hook port owned by the unified compaction pipeline. */
public interface CompactionHooks {
    public suspend fun onPreCompact(request: CompactionHookRequest)

    public suspend fun onPostCompact(request: CompactionHookRequest)
}

/** Compaction-hook implementation that performs no observation. */
public data object NoOpCompactionHooks : CompactionHooks {
    override suspend fun onPreCompact(request: CompactionHookRequest) {
    }

    override suspend fun onPostCompact(request: CompactionHookRequest) {
    }
}
