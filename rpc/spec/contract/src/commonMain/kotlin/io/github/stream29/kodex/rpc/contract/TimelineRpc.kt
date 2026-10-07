package io.github.stream29.kodex.rpc.contract

import kotlinx.coroutines.flow.Flow

/**
 * Common read-only contract for a timeline of a persisted root Agent.
 *
 * Each sessionIndex addresses that Session's backend owner for the selected timeline.
 * Cache nonce and latest index belong to this timeline, not the global Agent timeline
 * or a frontend HistoryIndexViewModel. The cache nonce is an in-memory random Long identifying
 * cache validity, not a counter, ordered business revision, or access credential.
 * Creating or rebuilding an owner and destructive content changes replace it.
 * Appends, keep-alive calls and value-cache eviction retain it. No cache nonce is persisted.
 *
 * Metadata getters are for initialization only; subsequent metadata comes from the flows.
 * Data queries remain available on demand and fail with CacheNonceMismatch on a cache-nonce
 * mismatch, rather than report absent data. Bind validation and reads to the same backend owner.
 * Read projections are eventually consistent: changes racing with a query may produce a
 * transiently inconsistent result. No atomic read snapshot or new guarded-read API is required.
 * Frontend invalidation and rejection of old read bindings provide the projection's convergence;
 * this does not relax the separate admission and nonce checks for history mutations.
 * Metadata flows publish replacement cache nonces normally, without failing for that change.
 * A failed read may be reconsidered using the subscribed cache nonce and invalidated cache;
 * the exception does not supply replacement metadata or authorize retrying the old request.
 * Cancelling a subscription does not close the Session.
 * Access requires an active Session and must fail with SessionNotActive without implicitly
 * loading an owner. Deactivation ends existing metadata upstreams with SessionNotActive.
 * Re-activate through keepSessionAlive before new access and initialize new subscriptions.
 * Invalidate old frontend bindings and caches; reject late results from an old binding or
 * cache nonce, including results that were valid when the backend read them.
 *
 * This ordinary generic interface is not an annotated RPC service. Concrete RPC services
 * must explicitly redeclare every method with override and concrete value types so the
 * kotlinx.rpc compiler plugin generates all service methods.
 *
 * Frontend cached views are provided separately by rpc/client; backend service wiring is separate.
 * This read-only contract exposes no storage writes.
 */
public interface TimelineRpc<T> {
    /** Reads the initial random cache-validity nonce for this timeline owner. */
    public suspend fun getCacheNonce(sessionIndex: Int): Long

    /** Includes the current cache nonce when collection begins, followed by replacements. */
    public fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>

    /** Reads the initial greatest stored index, or -1 for an empty timeline. */
    public suspend fun getLatestIndex(sessionIndex: Int): Int

    /** Includes the current greatest stored index when collection begins, followed by changes. */
    public fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    /** Reads the value visible at index; fails if no stored value exists at or before it. */
    public suspend fun get(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): T

    /** Returns the greatest stored index at or below index, or null if none exists. */
    public suspend fun floorToIndex(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): Int?

    /** Returns the smallest stored index at or above index, or null if none exists. */
    public suspend fun ceilToIndex(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): Int?

    /** Lists stored indexes in ascending order within the inclusive range; empty ranges yield empty lists. */
    public suspend fun indexesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Int>

    /** Reads exactly index, returning null only when absent under the requested cache nonce. */
    public suspend fun getExact(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): T?

    /** Reads exact entries in ascending index order within the inclusive range; empty ranges yield empty lists. */
    public suspend fun valuesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Pair<Int, T>>
}
