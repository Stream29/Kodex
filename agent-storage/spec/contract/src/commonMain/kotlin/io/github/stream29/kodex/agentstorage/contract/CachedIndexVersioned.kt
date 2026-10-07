package io.github.stream29.kodex.agentstorage.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only timeline view with observable backend cache metadata.
 *
 * Backend and frontend implementations have separate caches. The backend owns
 * the nonce; the frontend observes it to invalidate its cache and old read bindings.
 */
public interface CachedIndexVersioned<T> : IndexVersioned<T> {
    /**
     * Backend-issued in-memory cache identity, replaced on backend owner reconstruction
     * or destructive changes. Appends and value eviction preserve it.
     * A frontend observes this value rather than generating it. Compare for equality, not ordering.
     */
    public val cacheNonce: StateFlow<Long>

    /** Published tail of this timeline, or `-1` when empty; not the Agent's global index. */
    public val latestIndex: StateFlow<Int>
}
