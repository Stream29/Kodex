package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal suspend fun CoroutineScope.rpcCachedAgentStorage(
    sessionIndex: Int,
    rpc: AgentStorageRpc,
    valueCacheSize: Int = 1_024,
    timeSource: TimeSource = TimeSource.Monotonic,
): ObservableKodexAgentStorage = rpcCachedAgentStorage(
    sessionIndex = sessionIndex,
    uri = "kodex://session/$sessionIndex",
    rpc = rpc,
    valueCacheSize = valueCacheSize,
    timeSource = timeSource,
)

internal fun probeCommit(text: String): CleanIndexEntry =
    StableUserMessage(listOf(ContentItem.InputText(text)))

internal class TimelineProbeImpl : AgentStorageRpc {
    val nonce = MutableStateFlow(17L)
    val latest = MutableStateFlow(5)
    val entries = MutableStateFlow(
        mapOf(
            0 to listOf(probeCommit("zero")),
            2 to listOf(probeCommit("two")),
            5 to listOf(probeCommit("five")),
        ),
    )
    val active = MutableStateFlow(true)
    val metadataFailure = MutableStateFlow<Throwable?>(null)
    val subscriptions = MutableStateFlow(0)
    val calls = MutableStateFlow(emptyMap<String, Int>())
    var beforeGet: suspend (String) -> Unit = {}
    var beforeReturn: suspend (String) -> Unit = {}

    fun count(method: String): Int = calls.value[method] ?: 0

    private fun called(method: String, sessionIndex: Int) {
        assertEquals(7, sessionIndex)
        calls.update { it + (method to ((it[method] ?: 0) + 1)) }
    }

    override suspend fun getCacheNonce(sessionIndex: Int): Long {
        called("nonce", sessionIndex)
        beforeGet("nonce")
        if (!active.value) throw SessionNotActive()
        return nonce.value
    }

    override suspend fun getLatestIndex(sessionIndex: Int): Int {
        called("latest", sessionIndex)
        beforeGet("latest")
        if (!active.value) throw SessionNotActive()
        return latest.value
    }

    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> {
        assertEquals(7, sessionIndex)
        return observe(nonce)
    }

    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> {
        assertEquals(7, sessionIndex)
        return observe(latest)
    }

    private fun <T> observe(source: Flow<T>): Flow<T> = flow {
        subscriptions.update { it + 1 }
        try {
            emitAll(combine(source, active, metadataFailure) { value, active, failure ->
                if (!active) throw SessionNotActive()
                failure?.let { throw it }
                value
            })
        } finally {
            subscriptions.update { it - 1 }
        }
    }

    private suspend fun <T> read(
        method: String,
        sessionIndex: Int,
        nonce: Long,
        block: (Map<Int, List<AgentCommit>>) -> T,
    ): T {
        called(method, sessionIndex)
        if (!active.value) throw SessionNotActive()
        if (nonce != this.nonce.value) throw CacheNonceMismatch()
        val result = block(entries.value)
        beforeReturn(method)
        return result
    }

    override suspend fun countCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
    ): Int = read("count", sessionIndex, cacheNonce) { snapshot ->
        if (fromInclusive > toInclusive || types.isEmpty()) return@read 0
        snapshot.entries
            .filter { it.key in fromInclusive..toInclusive }
            .sumOf { (_, commits) -> commits.count { it.commitType in types } }
    }

    override suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        val method = when {
            fromInclusive == toInclusive -> "exact"
            limit == Int.MAX_VALUE -> "values"
            else -> "first"
        }
        return read(method, sessionIndex, cacheNonce) { snapshot ->
            if (fromInclusive > toInclusive || types.isEmpty() || limit <= 0) return@read emptyList()
            snapshot.entries
                .filter { it.key in fromInclusive..toInclusive }
                .sortedBy { it.key }
                .flatMap { (index, commits) ->
                    commits.filter { it.commitType in types }.map { IndexedAgentCommit(index, it) }
                }
                .take(limit)
        }
    }

    override suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = read("last", sessionIndex, cacheNonce) { snapshot ->
        if (fromInclusive > toInclusive || types.isEmpty() || limit <= 0) return@read emptyList()
        snapshot.entries
            .filter { it.key in fromInclusive..toInclusive }
            .sortedBy { it.key }
            .flatMap { (index, commits) ->
                commits.filter { it.commitType in types }.map { IndexedAgentCommit(index, it) }
            }
            .takeLast(limit)
    }

    override suspend fun compareAndSetSettings(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean = false
}

internal suspend fun withTimelineProbe(
    block: suspend CoroutineScope.(AgentStorageRpc, TimelineProbeImpl) -> Unit,
) {
    withTimeout(15.seconds) {
        val backend = TimelineProbeImpl()
        withInMemoryRpc(
            registerServices = { registerService(AgentStorageRpc::class) { backend } },
        ) { client ->
            block(RestoringRpcClient(client).withService<AgentStorageRpc>(), backend)
        }
    }
}
