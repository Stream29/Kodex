package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Read-only RPC service for the index timeline, following [TimelineRpc]'s semantics.
 * Cache nonce and latest index belong to this index-timeline owner.
 */
@Rpc
public interface IndexTimelineRpc : TimelineRpc<CleanIndexEntry> {
    public override suspend fun getCacheNonce(sessionIndex: Int): Long

    public override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>

    public override suspend fun getLatestIndex(sessionIndex: Int): Int

    public override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    public override suspend fun get(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): CleanIndexEntry

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
    ): CleanIndexEntry?

    public override suspend fun valuesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Pair<Int, CleanIndexEntry>>
}
