package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.contract.TokenCountDiagnostics
import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.rpc.contract.TokenCountTimelineRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

val tokenCountTimelineRpcTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("original token snapshots cross the generated service and frontend exact cache") {
        withTimeout(15.seconds) {
            val backend = TokenCountTestService()
            withInMemoryRpc(
                registerServices = { registerService(TokenCountTimelineRpc::class) { backend } },
            ) { client ->
                val rpc = RestoringRpcClient(client).withService<TokenCountTimelineRpc>()
                val nonce = rpc.getCacheNonce(7)
                assertEquals(5, rpc.getLatestIndex(7))
                assertEquals(backend.entries.getValue(2), rpc.get(7, nonce, 4))
                assertEquals(null, rpc.getExact(7, nonce, 4))
                assertEquals(2, rpc.floorToIndex(7, nonce, 4))
                assertEquals(5, rpc.ceilToIndex(7, nonce, 4))
                assertEquals(listOf(0, 2, 5), rpc.indexesIn(7, nonce, 0, 5))
                assertEquals(backend.entries.toList(), rpc.valuesIn(7, nonce, 0, 5))

                val ownerJob = Job(coroutineContext[Job])
                try {
                    val view = CoroutineScope(coroutineContext + ownerJob).rpcCachedIndexVersioned(7, rpc)
                    val before = backend.exactReads
                    for ((index, snapshot) in backend.entries) {
                        assertEquals(snapshot, view.getExact(index))
                        assertEquals(snapshot, view.getExact(index))
                    }
                    assertEquals(before + backend.entries.size, backend.exactReads)
                    assertEquals(nonce, view.cacheNonce.value)
                    assertEquals(5, view.latestIndex())
                } finally {
                    ownerJob.cancelAndJoin()
                }
                assertEquals(backend.entries.getValue(5), rpc.getExact(7, nonce, 5))
            }
        }
    }
}

/** Fixed test data; no production Session or storage implementation. */
private class TokenCountTestService : TokenCountTimelineRpc {
    val entries = mapOf(
        0 to TokenCountSnapshot(TokenCountKind.Legacy, 0),
        2 to TokenCountSnapshot(TokenCountKind.Response, 0, usage = TokenUsage(0, 0, 0)),
        5 to TokenCountSnapshot(
            kind = TokenCountKind.Response,
            totalTokens = 120,
            usage = TokenUsage(100, 20, 120),
            diagnostics = TokenCountDiagnostics(requestId = "request", turnStateReceived = true),
        ),
    )
    var exactReads = 0
    private val nonce = MutableStateFlow(17L)
    private val latest = MutableStateFlow(5)

    override suspend fun getCacheNonce(sessionIndex: Int): Long = nonce.value
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> = nonce
    override suspend fun getLatestIndex(sessionIndex: Int): Int = latest.value
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = latest

    override suspend fun get(sessionIndex: Int, cacheNonce: Long, index: Int): TokenCountSnapshot =
        entries.getValue(entries.keys.last { it <= index })

    override suspend fun getExact(sessionIndex: Int, cacheNonce: Long, index: Int): TokenCountSnapshot? {
        exactReads++
        return entries[index]
    }

    override suspend fun floorToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int? =
        entries.keys.lastOrNull { it <= index }

    override suspend fun ceilToIndex(sessionIndex: Int, cacheNonce: Long, index: Int): Int? =
        entries.keys.firstOrNull { it >= index }

    override suspend fun indexesIn(
        sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int,
    ): List<Int> = entries.keys.filter { it in fromInclusive..toInclusive }

    override suspend fun valuesIn(
        sessionIndex: Int, cacheNonce: Long, fromInclusive: Int, toInclusive: Int,
    ): List<Pair<Int, TokenCountSnapshot>> =
        entries.filterKeys { it in fromInclusive..toInclusive }.toList()
}
