package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Read-only RPC contract for the work timeline of a persisted root Agent.
 *
 * Follows [TimelineRpc]'s initialization-only metadata getters, current-value flows,
 * cache-nonce-checked data queries, and inclusive range semantics. The cache nonce and
 * latest index belong to this work-timeline owner. Cancelling a subscription does not
 * close the Session. This contract has no implementation and exposes no storage writes.
 */
@Rpc
public interface WorkTimelineRpc : TimelineRpc<StableWorkEvent> {
    public override suspend fun getCacheNonce(sessionIndex: Int): Long

    public override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>

    public override suspend fun getLatestIndex(sessionIndex: Int): Int

    public override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    public override suspend fun get(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): StableWorkEvent

    public override suspend fun floorToIndex(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): Int?

    public override suspend fun ceilToIndex(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): Int?

    public override suspend fun indexesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Int>

    public override suspend fun getExact(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): StableWorkEvent?

    public override suspend fun valuesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Pair<Int, StableWorkEvent>>
}
