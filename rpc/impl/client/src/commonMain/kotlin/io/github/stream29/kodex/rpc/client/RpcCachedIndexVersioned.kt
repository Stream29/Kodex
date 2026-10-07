package io.github.stream29.kodex.rpc.client

import io.github.reactivecircus.cache4k.Cache
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.rpc.contract.TimelineRpc
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

/**
 * Opens one frontend timeline binding in this owner's lifetime.
 *
 * [rpc] must come from the restoring client. The Session must already be active.
 * Only existing exact entries are cached; metadata is initialized once and then observed.
 * Cancelling the owner releases this binding, not the shared client or backend Session.
 * Metadata failure invalidates the binding and follows the owner's exception policy.
 * There is no activation, resubscription, query retry or frontend-generated nonce.
 */
public suspend fun <T : Any> CoroutineScope.rpcCachedIndexVersioned(
    sessionIndex: Int,
    rpc: TimelineRpc<T>,
): CachedIndexVersioned<T> = rpcCachedIndexVersioned(
    sessionIndex, rpc, valueCacheSize = 1_024, timeSource = TimeSource.Monotonic,
)

internal suspend fun <T : Any> CoroutineScope.rpcCachedIndexVersioned(
    sessionIndex: Int,
    rpc: TimelineRpc<T>,
    valueCacheSize: Int,
    timeSource: TimeSource,
): CachedIndexVersioned<T> {
    require(valueCacheSize >= 0)
    val owner = requireNotNull(coroutineContext[Job]) { "A timeline binding requires an owner Job." }
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
        return RpcCachedIndexVersioned(
            sessionIndex, rpc, binding, nonce, latest, valueCacheSize, timeSource,
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
    // Ordinary failures already fail the binding's regular Job. A cancelled/finished
    // metadata producer must also stop the sibling producer, even under a SupervisorJob owner.
    when (cause) {
        null -> binding.cancel("Timeline metadata stream completed.")
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

private class RpcCachedIndexVersioned<T : Any>(
    private val sessionIndex: Int,
    private val rpc: TimelineRpc<T>,
    private val binding: Job,
    override val cacheNonce: StateFlow<Long>,
    override val latestIndex: StateFlow<Int>,
    valueCacheSize: Int,
    timeSource: TimeSource,
) : CachedIndexVersioned<T> {
    private val mutex = Mutex()
    private var valuesNonce: Long? = null
    private val values = Cache.Builder<Int, T>()
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

    override suspend fun latestIndex(): Int = cached { latestIndex.value }

    override suspend fun get(index: Int): T = query { rpc.get(sessionIndex, it, index) }

    override suspend fun getExact(index: Int): T? {
        val (nonce, hit) = cached { it to values.get(index) }
        if (hit != null) return hit
        val value = remote { rpc.getExact(sessionIndex, nonce, index) }
        return cached(nonce) {
            if (value != null) values.put(index, value)
            value
        }
    }

    override suspend fun floorToIndex(index: Int): Int? =
        query { rpc.floorToIndex(sessionIndex, it, index) }

    override suspend fun ceilToIndex(index: Int): Int? =
        query { rpc.ceilToIndex(sessionIndex, it, index) }

    override suspend fun indexesIn(range: IntRange): List<Int> =
        query { rpc.indexesIn(sessionIndex, it, range.first, range.last) }

    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> {
        val nonce = cached { it }
        val result = remote { rpc.valuesIn(sessionIndex, nonce, range.first, range.last) }
        return cached(nonce) {
            result.forEach { (index, value) -> values.put(index, value) }
            result
        }
    }

    private suspend fun <R> query(block: suspend (Long) -> R): R {
        val nonce = cached { it }
        val result = remote { block(nonce) }
        return cached(nonce) { result }
    }

    private suspend fun <R> remote(block: suspend () -> R): R = try {
        binding.read(block)
    } catch (failure: SessionNotActive) {
        binding.cancel(CancellationException("Timeline Session is inactive.", failure))
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
