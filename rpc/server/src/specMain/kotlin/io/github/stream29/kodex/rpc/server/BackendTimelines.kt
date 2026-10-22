@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.contract.*
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

public expect class BackendIndexTimelineRpc(host: BackendSessionHost) : IndexTimelineRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): CleanIndexEntry
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): CleanIndexEntry?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int>
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, CleanIndexEntry>>
}

public expect class BackendWorkTimelineRpc(host: BackendSessionHost) : WorkTimelineRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): StableWorkEvent
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): StableWorkEvent?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int>
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, StableWorkEvent>>
}

public expect class BackendTimestampTimelineRpc(host: BackendSessionHost) : TimestampTimelineRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): Instant
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): Instant?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int>
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, Instant>>
}

public expect class BackendTokenCountTimelineRpc(host: BackendSessionHost) : TokenCountTimelineRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): TokenCountSnapshot
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): TokenCountSnapshot?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int>
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, TokenCountSnapshot>>
}

public expect class BackendUnstableTimelineRpc(host: BackendSessionHost) : UnstableTimelineRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): List<UnstableCleanEvent>
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): List<UnstableCleanEvent>?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int>
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, List<UnstableCleanEvent>>>
}

public expect class BackendSettingsTimelineRpc(host: BackendSessionHost) : SettingsTimelineRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): KodexAgentSettings
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): KodexAgentSettings?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int>
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, KodexAgentSettings>>
    override suspend fun compareAndSet(sessionIndex: Int, expect: KodexAgentSettings, update: KodexAgentSettings): Boolean
}
