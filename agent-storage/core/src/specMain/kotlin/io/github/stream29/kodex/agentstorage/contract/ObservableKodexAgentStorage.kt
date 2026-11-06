package io.github.stream29.kodex.agentstorage.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only storage view exposing observable backend cache metadata.
 *
 * Backend and frontend implementations have separate caches. The backend owns
 * [cacheNonce]; the frontend observes it to invalidate its cache and old read bindings.
 */
public interface ObservableKodexAgentStorage : KodexAgentStorage {
    /**
     * Backend-issued in-memory cache identity, replaced on backend owner reconstruction
     * or destructive changes (`revert`). Appends and value eviction preserve it.
     * Compare for equality, not ordering.
     */
    public val cacheNonce: StateFlow<Long>

    /** Published tail of this storage, or `-1` when empty. */
    public val latestIndexFlow: StateFlow<Int>
}
