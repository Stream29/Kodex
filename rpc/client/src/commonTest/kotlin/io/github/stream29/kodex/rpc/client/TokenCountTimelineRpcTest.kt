package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountDiagnostics
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.contract.ceilToIndex
import io.github.stream29.kodex.agentstorage.contract.floorToIndex
import io.github.stream29.kodex.agentstorage.contract.getTokenCountAt
import io.github.stream29.kodex.agentstorage.contract.getTokenCountExact
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
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
    test("sparse traversal crosses generated RPC without gap probes or extra values") {
        withTimeout(15.seconds) {
            val backend = TokenCountTestService(
                listOf(0, 5, 10_000, 50_000).associateWith {
                    TokenCountSnapshot.Legacy(it.toLong())
                },
            )
            withInMemoryRpc(
                registerServices = { registerService(AgentStorageRpc::class) { backend } },
            ) { client ->
                val rpc = RestoringRpcClient(client).withService<AgentStorageRpc>()
                val ownerJob = Job(coroutineContext[Job])
                try {
                    val view = CoroutineScope(coroutineContext + ownerJob).rpcCachedAgentStorage(7, rpc)
                    val last = view.getLastCommitsIn(0..49_999, AgentCommitType.TokenCountTypes, limit = 1).single()
                    assertEquals(10_000, last.index)
                    assertEquals(1, backend.lastQueries)
                    assertEquals(0, backend.indexQueries)
                    assertEquals(0, backend.valueQueries)
                } finally {
                    ownerJob.cancelAndJoin()
                }
            }
        }
    }

    test("original token snapshots cross the generated service and frontend exact cache") {
        withTimeout(15.seconds) {
            val backend = TokenCountTestService()
            withInMemoryRpc(
                registerServices = { registerService(AgentStorageRpc::class) { backend } },
            ) { client ->
                val rpc = RestoringRpcClient(client).withService<AgentStorageRpc>()
                val nonce = rpc.getCacheNonce(7)
                assertEquals(5, rpc.getLatestIndex(7))
                assertEquals(emptyList(), rpc.getCommitsExact(7, nonce, 4, AgentCommitType.TokenCountTypes))
                assertEquals(listOf(0, 2, 5), rpc.indexesIn(7, nonce, 0, 5, AgentCommitType.TokenCountTypes))
                assertEquals(
                    backend.entries.map { IndexedAgentCommit(it.key, it.value) },
                    rpc.getCommitsIn(7, nonce, 0, 5, AgentCommitType.TokenCountTypes),
                )

                val ownerJob = Job(coroutineContext[Job])
                try {
                    val view = CoroutineScope(coroutineContext + ownerJob).rpcCachedAgentStorage(7, rpc)
                    assertEquals(null, view.getTokenCountExact(4))
                    val before = backend.exactReads
                    for ((index, snapshot) in backend.entries) {
                        assertEquals(snapshot, view.getTokenCountExact(index))
                        assertEquals(snapshot, view.getTokenCountExact(index))
                    }
                    assertEquals(before + backend.entries.size, backend.exactReads)
                    assertEquals(backend.entries.getValue(2), view.getTokenCountAt(4))
                    assertEquals(2, view.floorToIndex(4, AgentCommitType.TokenCountTypes))
                    assertEquals(5, view.ceilToIndex(4, AgentCommitType.TokenCountTypes))
                    assertEquals(nonce, view.cacheNonce.value)
                    assertEquals(5, view.latestIndex())
                } finally {
                    ownerJob.cancelAndJoin()
                }
                assertEquals(
                    listOf(backend.entries.getValue(5)),
                    rpc.getCommitsExact(7, nonce, 5, AgentCommitType.TokenCountTypes),
                )
            }
        }
    }
}

private class TokenCountTestService(
    val entries: Map<Int, TokenCountSnapshot> = mapOf(
        0 to TokenCountSnapshot.Legacy(0),
        2 to TokenCountSnapshot.Response(0, usage = TokenUsage(0, 0, 0)),
        5 to TokenCountSnapshot.Response(
            totalTokens = 120,
            usage = TokenUsage(100, 20, 120),
            diagnostics = TokenCountDiagnostics(requestId = "request", turnStateReceived = true),
        ),
    ),
) : AgentStorageRpc {
    var exactReads = 0
    var lastQueries = 0
    var indexQueries = 0
    var valueQueries = 0
    var returnedValues = 0
    private val nonce = MutableStateFlow(17L)
    private val latest = MutableStateFlow(entries.keys.maxOrNull() ?: -1)

    override suspend fun getCacheNonce(sessionIndex: Int): Long = nonce.value
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> = nonce
    override suspend fun getLatestIndex(sessionIndex: Int): Int = latest.value
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = latest

    override suspend fun indexesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
    ): List<Int> {
        indexQueries++
        return entries.filter { (index, commit) -> index in fromInclusive..toInclusive && commit.commitType in types }
            .keys
            .toList()
    }

    override suspend fun getCommitsExact(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
        types: Set<AgentCommitType>,
    ): List<AgentCommit> {
        exactReads++
        return listOfNotNull(entries[index]).filter { it.commitType in types }
    }

    override suspend fun getCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
    ): List<IndexedAgentCommit> {
        valueQueries++
        return entries
            .filter { (index, commit) -> index in fromInclusive..toInclusive && commit.commitType in types }
            .map { IndexedAgentCommit(it.key, it.value) }
            .also { returnedValues += it.size }
    }

    override suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> =
        entries
            .filter { (index, commit) -> index in fromInclusive..toInclusive && commit.commitType in types }
            .map { IndexedAgentCommit(it.key, it.value) }
            .take(limit)

    override suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        lastQueries++
        return entries
            .filter { (index, commit) -> index in fromInclusive..toInclusive && commit.commitType in types }
            .map { IndexedAgentCommit(it.key, it.value) }
            .takeLast(limit)
    }

    override suspend fun compareAndSetSettings(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean = false
}
