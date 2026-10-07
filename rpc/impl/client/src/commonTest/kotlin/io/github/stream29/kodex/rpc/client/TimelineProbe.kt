package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.rpc.contract.TimelineRpc
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
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@Rpc
internal interface TimelineProbe : TimelineRpc<String> {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): String
    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): String?
    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int?
    override suspend fun indexesIn(
        sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int,
    ): List<Int>
    override suspend fun valuesIn(
        sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int,
    ): List<Pair<Int, String>>
}

internal class TimelineProbeImpl : TimelineProbe {
    val nonce = MutableStateFlow(17L)
    val latest = MutableStateFlow(5)
    val entries = MutableStateFlow(mapOf(0 to "zero", 2 to "two", 5 to "five"))
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
        method: String, sessionIndex: Int, nonce: Long, block: (Map<Int, String>) -> T,
    ): T {
        called(method, sessionIndex)
        if (!active.value) throw SessionNotActive()
        if (nonce != this.nonce.value) throw CacheNonceMismatch()
        val result = block(entries.value)
        beforeReturn(method)
        return result
    }

    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): String =
        read("get", sessionIndex, cacheNonce) { snapshot ->
            val stored = snapshot.keys.filter { it <= index }.maxOrNull()
                ?: throw IllegalArgumentException("No predecessor")
            snapshot.getValue(stored)
        }

    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): String? =
        read("exact", sessionIndex, cacheNonce) { it[index] }

    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int? =
        read("floor", sessionIndex, cacheNonce) { it.keys.filter { key -> key <= index }.maxOrNull() }

    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int? =
        read("ceil", sessionIndex, cacheNonce) { it.keys.filter { key -> key >= index }.minOrNull() }

    override suspend fun indexesIn(
        sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int,
    ): List<Int> = read("indexes", sessionIndex, cacheNonce) {
        it.keys.filter { index -> index in fromInclusive..toInclusive }.sorted()
    }

    override suspend fun valuesIn(
        sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int,
    ): List<Pair<Int, String>> = read("values", sessionIndex, cacheNonce) { snapshot ->
        snapshot.keys.filter { index -> index in fromInclusive..toInclusive }
            .sorted().map { it to snapshot.getValue(it) }
    }
}

internal suspend fun withTimelineProbe(
    block: suspend CoroutineScope.(TimelineProbe, TimelineProbeImpl) -> Unit,
) {
    withTimeout(15.seconds) {
        val backend = TimelineProbeImpl()
        withInMemoryRpc(
            registerServices = { registerService(TimelineProbe::class) { backend } },
        ) { client ->
            block(RestoringRpcClient(client).withService<TimelineProbe>(), backend)
        }
    }
}
