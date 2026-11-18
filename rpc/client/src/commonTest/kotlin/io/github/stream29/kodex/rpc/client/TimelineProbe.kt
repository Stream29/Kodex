package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.GeneratedImageArtifact
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
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

internal abstract class StubAgentRuntimeRpc : AgentRuntimeRpc {
    override suspend fun getState(sessionIndex: Int): AgentStateValue = throw UnsupportedOperationException()
    override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue> = throw UnsupportedOperationException()
    override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> =
        throw UnsupportedOperationException()
    override suspend fun submit(sessionIndex: Int, content: List<ContentItem>) = throw UnsupportedOperationException()
    override suspend fun submitAskUserAnswer(
        sessionIndex: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ) = throw UnsupportedOperationException()
    override suspend fun createAskUser(
        sessionIndex: Int,
        arguments: RequestUserInputArgs,
    ): AskUserEntry = throw UnsupportedOperationException()
    override suspend fun runWebSearch(
        sessionIndex: Int,
        commands: SearchCommands,
    ): SearchResponse = throw UnsupportedOperationException()
    override suspend fun generateImage(
        sessionIndex: Int,
        arguments: ImageGenToolArguments,
    ): GeneratedImageArtifact = throw UnsupportedOperationException()
    override suspend fun resume(sessionIndex: Int) = throw UnsupportedOperationException()
    override suspend fun forcedCompact(sessionIndex: Int): Int = throw UnsupportedOperationException()
    override suspend fun clearPending(sessionIndex: Int): Int = throw UnsupportedOperationException()
    override suspend fun revertHistory(
        sessionIndex: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ) = throw UnsupportedOperationException()
    override suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState> =
        throw UnsupportedOperationException()
    override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>> =
        throw UnsupportedOperationException()
    override suspend fun readShellSessionOutput(
        sessionIndex: Int,
        shellSessionId: Int,
        offset: ULong,
        limit: UInt,
        expectedRegistrationNonce: String?,
    ): StdoutSlice = throw UnsupportedOperationException()
    override suspend fun writeShellSessionStdin(
        sessionIndex: Int,
        shellSessionId: Int,
        chars: String,
        closeInput: Boolean,
    ) = throw UnsupportedOperationException()
    override suspend fun closeShellSession(sessionIndex: Int, shellSessionId: Int, expectedRegistrationNonce: String?) =
        throw UnsupportedOperationException()
    override suspend fun getRunningTurn(sessionIndex: Int): Boolean = throw UnsupportedOperationException()
    override fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean> = throw UnsupportedOperationException()
    override suspend fun cancelRunningTurn(sessionIndex: Int) = throw UnsupportedOperationException()
}

internal fun probeCommit(text: String): IndexCommit =
    StableUserMessage(listOf(ContentItem.InputText(text)))

internal class TimelineProbeImpl : StubAgentRuntimeRpc() {
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
    block: suspend CoroutineScope.(AgentRuntimeRpc, TimelineProbeImpl) -> Unit,
) {
    withTimeout(15.seconds) {
        val backend = TimelineProbeImpl()
        withInMemoryRpc(
            registerServices = { registerService(AgentRuntimeRpc::class) { backend } },
        ) { client ->
            block(RestoringRpcClient(client).withService<AgentRuntimeRpc>(), backend)
        }
    }
}
