package io.github.stream29.kodex.rpc.client

import io.github.reactivecircus.cache4k.Cache
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

public actual suspend fun CoroutineScope.rpcCachedAgentStorage(
    sessionIndex: Int,
    uri: String,
    rpc: AgentStorageRpc,
): ObservableKodexAgentStorage = rpcCachedAgentStorage(
    sessionIndex = sessionIndex,
    uri = uri,
    rpc = rpc,
    valueCacheSize = 1_024,
    timeSource = TimeSource.Monotonic,
)

internal suspend fun CoroutineScope.rpcCachedAgentStorage(
    sessionIndex: Int,
    uri: String,
    rpc: AgentStorageRpc,
    valueCacheSize: Int,
    timeSource: TimeSource,
): ObservableKodexAgentStorage {
    require(valueCacheSize >= 0)
    val owner = requireNotNull(coroutineContext[Job]) { "A storage binding requires an owner Job." }
    owner.ensureActive()
    val binding = Job(owner)
    val scope = CoroutineScope(coroutineContext + binding)
    try {
        val nonce = scope.rpcStateIn(
            get = { binding.read { rpc.getCacheNonce(sessionIndex) } },
            getFlow = { rpc.getCacheNonceFlow(sessionIndex).untilBindingEnds(binding) },
        )
        val latest = scope.rpcStateIn(
            get = { binding.read { rpc.getLatestIndex(sessionIndex) } },
            getFlow = { rpc.getLatestIndexFlow(sessionIndex).untilBindingEnds(binding) },
        )
        currentCoroutineContext().ensureActive()
        binding.ensureActive()
        return RpcCachedAgentStorage(
            sessionIndex = sessionIndex,
            uri = uri,
            rpc = rpc,
            binding = binding,
            cacheNonce = nonce,
            latestIndexFlow = latest,
            valueCacheSize = valueCacheSize,
            timeSource = timeSource,
        ).also { view ->
            binding.invokeOnCompletion { view.clearValues() }
            scope.launch { nonce.collect { view.invalidateOldValues() } }
        }
    } catch (failure: Throwable) {
        try {
            withContext(NonCancellable) {
                withTimeout(10.seconds) { binding.cancelAndJoin() }
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

private fun <T> Flow<T>.untilBindingEnds(binding: CompletableJob): Flow<T> = onCompletion { cause ->
    when (cause) {
        null -> binding.cancel("Storage metadata stream completed.")
        is CancellationException -> binding.cancel(cause)
    }
}

/** Read operations belong to their caller but must also stop when this binding closes. */
private suspend fun <R> Job.read(block: suspend () -> R): R = coroutineScope {
    val caller = currentCoroutineContext().job
    val release = this@read.invokeOnCompletion { caller.cancel() }
    try {
        this@read.ensureActive()
        block()
    } finally {
        release.dispose()
    }
}

private data class ExactCacheKey(
    val index: Int,
    val types: Set<AgentCommitType>,
)

private val SingleCommitCategorySets: Set<Set<AgentCommitType>> = setOf(
    AgentCommitType.IndexTypes,
    AgentCommitType.IndexEventTypes,
    AgentCommitType.WorkTypes,
    AgentCommitType.SettingsTypes,
    AgentCommitType.LlmRequestMetaTypes,
    AgentCommitType.UnstableTypes,
)

private class RpcCachedAgentStorage(
    private val sessionIndex: Int,
    override val uri: String,
    private val rpc: AgentStorageRpc,
    private val binding: Job,
    override val cacheNonce: StateFlow<Long>,
    override val latestIndexFlow: StateFlow<Int>,
    valueCacheSize: Int,
    timeSource: TimeSource,
) : ObservableKodexAgentStorage {
    private val mutex = Mutex()
    private var valuesNonce: Long? = null
    private val values = Cache.Builder<ExactCacheKey, List<IndexedAgentCommit>>()
        .maximumCacheSize(valueCacheSize.toLong())
        .expireAfterAccess(60.seconds)
        .timeSource(timeSource)
        .build()

    fun clearValues() {
        values.invalidateAll()
    }

    suspend fun invalidateOldValues() {
        mutex.withLock {
            binding.ensureActive()
            val nonce = cacheNonce.value
            if (valuesNonce != nonce) {
                clearValues()
                valuesNonce = nonce
            }
        }
    }

    override suspend fun latestIndex(): Int = cached { latestIndexFlow.value }

    override suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): Int {
        val nonce = cached { it }
        val result = remote {
            rpc.countCommitsIn(sessionIndex, nonce, range.first, range.last, types)
        }
        return cached(nonce) { result }
    }

    override suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        val exactKey = ExactCacheKey(range.first, types).takeIf { range.first == range.last && limit > 0 }
        val (nonce, hit) = cached { it to exactKey?.let(values::get) }
        if (hit != null) return hit.take(limit)
        val result = remote {
            rpc.getFirstCommitsIn(sessionIndex, nonce, range.first, range.last, types, limit)
        }
        return cached(nonce) {
            cacheResult(result, types, limit)
            result
        }
    }

    override suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        val exactKey = ExactCacheKey(range.first, types).takeIf { range.first == range.last && limit > 0 }
        val (nonce, hit) = cached { it to exactKey?.let(values::get) }
        if (hit != null) return hit.takeLast(limit)
        val result = remote {
            rpc.getLastCommitsIn(sessionIndex, nonce, range.first, range.last, types, limit)
        }
        return cached(nonce) {
            cacheResult(result, types, limit)
            result
        }
    }

    private fun cacheResult(
        result: List<IndexedAgentCommit>,
        types: Set<AgentCommitType>,
        limit: Int,
    ) {
        if (limit == Int.MAX_VALUE) {
            result.groupBy { it.index }.forEach { (idx, commits) ->
                if (commits.isNotEmpty()) {
                    values.put(ExactCacheKey(idx, types), commits)
                }
            }
        } else if (types in SingleCommitCategorySets) {
            result.forEach { indexed ->
                values.put(ExactCacheKey(indexed.index, types), listOf(indexed))
            }
        }
    }

    private suspend fun <R> remote(block: suspend () -> R): R = try {
        binding.read(block)
    } catch (failure: SessionNotActive) {
        binding.cancel(CancellationException("Storage Session is inactive.", failure))
        throw failure
    }

    /** Only local cache operations are serialized, never the RPC or backend storage. */
    private suspend fun <R> cached(expectedNonce: Long? = null, action: (Long) -> R): R =
        mutex.withLock {
            ensureActive()
            val nonce = cacheNonce.value
            if (valuesNonce != nonce) {
                clearValues()
                valuesNonce = nonce
            }
            if (expectedNonce != null && expectedNonce != nonce) throw CacheNonceMismatch()
            val result = action(nonce)
            // Also cover cancellation/metadata publication racing with the synchronous cache call.
            ensureActive()
            if (cacheNonce.value != nonce) {
                clearValues()
                throw CacheNonceMismatch()
            }
            result
        }

    private suspend fun ensureActive() {
        if (!binding.isActive) clearValues()
        binding.ensureActive()
        currentCoroutineContext().ensureActive()
    }
}
