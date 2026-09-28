package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Instant

/** Uses the actual cache metadata, never a service-owned index or random marker. */
internal class BackendTimeline<T>(
    private val host: BackendSessionHost,
    private val select: (ObservableKodexAgentStorage) -> CachedIndexVersioned<T>,
) : TimelineRpc<T> {
    private suspend fun cache(sessionIndex: Int): CachedIndexVersioned<T> =
        select(host.session(sessionIndex).session.storage as ObservableKodexAgentStorage)

    private suspend fun checked(sessionIndex: Int, nonce: Long): CachedIndexVersioned<T> =
        cache(sessionIndex).also { if (it.cacheNonce.value != nonce) throw CacheNonceMismatch() }

    override suspend fun getCacheNonce(sessionIndex: Int): Long = cache(sessionIndex).cacheNonce.value
    override suspend fun getLatestIndex(sessionIndex: Int): Int = cache(sessionIndex).latestIndex.value
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> = flow {
        val binding = host.session(sessionIndex)
        emitAll(binding.observe(select(binding.session.storage as ObservableKodexAgentStorage).cacheNonce))
    }
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = flow {
        val binding = host.session(sessionIndex)
        emitAll(binding.observe(select(binding.session.storage as ObservableKodexAgentStorage).latestIndex))
    }
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): T =
        checked(sessionIndex, cacheNonce)[index]
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): T? =
        checked(sessionIndex, cacheNonce).getExact(index)
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int? =
        checked(sessionIndex, cacheNonce).floorToIndex(index)
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int? =
        checked(sessionIndex, cacheNonce).ceilToIndex(index)
    override suspend fun indexesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Int> =
        checked(sessionIndex, cacheNonce).indexesIn(fromInclusive..toInclusive)
    override suspend fun valuesIn(sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int): List<Pair<Int, T>> =
        checked(sessionIndex, cacheNonce).valuesIn(fromInclusive..toInclusive)
}

public class BackendIndexTimelineRpc(host: BackendSessionHost) :
    IndexTimelineRpc, TimelineRpc<CleanIndexEntry> by BackendTimeline(host, { it.index })

public class BackendWorkTimelineRpc(host: BackendSessionHost) :
    WorkTimelineRpc, TimelineRpc<StableWorkEvent> by BackendTimeline(host, { it.work })

public class BackendTimestampTimelineRpc(host: BackendSessionHost) :
    TimestampTimelineRpc, TimelineRpc<Instant> by BackendTimeline(host, { it.timestamp })

public class BackendTokenCountTimelineRpc(host: BackendSessionHost) :
    TokenCountTimelineRpc, TimelineRpc<TokenCountSnapshot> by BackendTimeline(host, { it.tokenCount })

public class BackendUnstableTimelineRpc(host: BackendSessionHost) :
    UnstableTimelineRpc, TimelineRpc<List<UnstableCleanEvent>> by BackendTimeline(host, { it.unstable })

public class BackendSettingsTimelineRpc(private val host: BackendSessionHost) :
    SettingsTimelineRpc, TimelineRpc<KodexAgentSettings> by BackendTimeline(host, { it.settings }) {
    override suspend fun compareAndSet(sessionIndex: Int, expect: KodexAgentSettings, update: KodexAgentSettings): Boolean =
        host.inSession(sessionIndex) { runtime.compareAndSetSettings(expect, update) }
}
