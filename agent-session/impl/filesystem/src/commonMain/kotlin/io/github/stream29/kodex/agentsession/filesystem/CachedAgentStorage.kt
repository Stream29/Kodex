package io.github.stream29.kodex.agentsession.filesystem

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.SafeRw
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.reactivecircus.cache4k.Cache
import io.github.reactivecircus.cache4k.CacheEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal suspend fun FileSystemAgentStorage.cached(
    ownerScope: CoroutineScope,
    valueCacheSize: Int,
): CachedAgentStorage {
    require(valueCacheSize > 0) { "Value cache size must be positive." }
    check(ownerScope.isActive) { "AgentSession storage is closed." }
    return CachedAgentStorage(
        ownerScope = ownerScope,
        backing = this,
        backingUri = uri,
        cachedIndex = index.cached(ownerScope, valueCacheSize),
        cachedWork = work.cached(ownerScope, valueCacheSize),
        cachedSettings = settings.cached(ownerScope, valueCacheSize),
        cachedTimestamp = timestamp.cached(ownerScope, valueCacheSize),
        cachedTokenCount = tokenCount.cached(ownerScope, valueCacheSize),
        cachedUnstable = unstable.cached(ownerScope, valueCacheSize),
    )
}

/** Session-owned cache over one filesystem AgentStorage. */
internal class CachedAgentStorage internal constructor(
    private val ownerScope: CoroutineScope,
    internal val backing: FileSystemAgentStorage,
    private val backingUri: String,
    private val cachedIndex: CachedIndexVersionedImpl<CleanIndexEntry>,
    private val cachedWork: CachedIndexVersionedImpl<StableWorkEvent>,
    private val cachedSettings: CachedIndexVersionedImpl<KodexAgentSettings>,
    private val cachedTimestamp: CachedIndexVersionedImpl<kotlin.time.Instant>,
    private val cachedTokenCount: CachedIndexVersionedImpl<TokenCountSnapshot>,
    private val cachedUnstable: CachedIndexVersionedImpl<List<UnstableCleanEvent>>,
) : MutableKodexAgentStorage, ObservableKodexAgentStorage {
    override val uri: String
        get() {
            requireActive()
            return backingUri
        }
    override val index: CachedIndexVersionedImpl<CleanIndexEntry>
        get() {
            requireActive()
            return cachedIndex
        }
    override val work: CachedIndexVersionedImpl<StableWorkEvent>
        get() {
            requireActive()
            return cachedWork
        }
    override val settings: CachedIndexVersionedImpl<KodexAgentSettings>
        get() {
            requireActive()
            return cachedSettings
        }
    override val timestamp: CachedIndexVersionedImpl<kotlin.time.Instant>
        get() {
            requireActive()
            return cachedTimestamp
        }
    override val tokenCount: CachedIndexVersionedImpl<TokenCountSnapshot>
        get() {
            requireActive()
            return cachedTokenCount
        }
    override val unstable: CachedIndexVersionedImpl<List<UnstableCleanEvent>>
        get() {
            requireActive()
            return cachedUnstable
        }

    private fun requireActive() {
        check(ownerScope.isActive) { "AgentSession storage is closed." }
    }
}

private suspend fun <T : Any> FileSystemIndexVersioned<T>.cached(
    ownerScope: CoroutineScope,
    valueCacheSize: Int,
): CachedIndexVersionedImpl<T> {
    val indexes = storedIndexes()
    reconcileLatestIndexUnsafe(indexes.lastOrNull() ?: -1)
    return CachedIndexVersionedImpl(
        ownerScope = ownerScope,
        delegate = this,
        valueCacheSize = valueCacheSize,
        indexes = indexes,
    )
}

/**
 * Session-owned sparse timeline. If a failed append cannot be reconciled with
 * numbered records, cached queries and mutations reject uncertain authority
 * until the owning Session is reopened. Metadata flows retain last-known
 * observations; they do not certify the durable tail or continued admission.
 */
internal class CachedIndexVersionedImpl<T : Any>(
    ownerScope: CoroutineScope,
    private val delegate: FileSystemIndexVersioned<T>,
    private val valueCacheSize: Int,
    indexes: List<Int>,
    timeSource: TimeSource = TimeSource.Monotonic,
    cleanupInterval: Duration = CachedValueTtl,
    cacheEventListener: CacheEventListener<Int, T>? = null,
) : MutableIndexVersioned<T>, CachedIndexVersioned<T>, CoroutineScope by ownerScope.supervisorChildScope() {
    private val mutableCacheNonce = MutableStateFlow(Random.nextLong())
    private val mutableLatestIndex = MutableStateFlow(indexes.lastOrNull() ?: -1)

    /** In-memory cache identity, not an authority/health signal. */
    override val cacheNonce: StateFlow<Long> = mutableCacheNonce.asStateFlow()

    /** Last-known tail of this timeline, independent of the Agent's global storage index. */
    override val latestIndex: StateFlow<Int> = mutableLatestIndex.asStateFlow()

    private val indexes = SafeRw<List<Int>, MutableList<Int>>(
        indexes.toMutableList(),
    )
    // Read and written only within indexes sessions. Delegate mutations and
    // reconciliation hold the same write session, so no stale admission can
    // race the failure or clear uncertainty from a different operation.
    private var unresolvedFailure: Throwable? = null
    private val values = Cache.Builder<Int, T>()
        .expireAfterAccess(CachedValueTtl)
        .maximumCacheSize(valueCacheSize.toLong())
        .timeSource(timeSource)
        .apply { cacheEventListener?.let(::eventListener) }
        .build()

    init {
        coroutineContext[Job]?.invokeOnCompletion {
            values.invalidateAll()
        }
        launch {
            while (isActive) {
                delay(cleanupInterval)
                values.invalidate(CacheCleanupKey)
            }
        }
    }

    override suspend fun latestIndex(): Int {
        requireActive()
        return indexes.readSession {
            requireAuthority()
            it.lastOrNull() ?: -1
        }
    }

    override suspend fun get(index: Int): T {
        requireActive()
        require(index >= 0) { "Index $index must be non-negative." }
        return indexes.readSession { snapshot ->
            requireAuthority()
            val position = snapshot.binarySearch(index)
            val floorPosition = if (position >= 0) position else -position - 2
            val storedIndex = snapshot.getOrNull(floorPosition)
                ?: throw IllegalArgumentException("No value is visible at index $index.")
            val value = values.get(storedIndex) { delegate.getUnsafe(storedIndex) }
            if (!isActive) {
                values.invalidate(storedIndex)
                requireActive()
            }
            value
        }
    }

    override suspend fun getExact(index: Int): T? {
        requireActive()
        require(index >= 0) { "Index $index must be non-negative." }
        return indexes.readSession { snapshot ->
            requireAuthority()
            val stored = snapshot.binarySearch(index).takeIf { it >= 0 }?.let { snapshot[it] }
                ?: return@readSession null
            val value = values.get(stored) { delegate.getUnsafe(stored) }
            if (!isActive) {
                values.invalidate(stored)
                requireActive()
            }
            value
        }
    }

    override suspend fun floorToIndex(index: Int): Int? {
        requireActive()
        return indexes.readSession { snapshot ->
            requireAuthority()
            val position = snapshot.binarySearch(index)
            snapshot.getOrNull(if (position >= 0) position else -position - 2)
        }
    }

    override suspend fun ceilToIndex(index: Int): Int? {
        requireActive()
        return indexes.readSession { snapshot ->
            requireAuthority()
            val position = snapshot.binarySearch(index)
            snapshot.getOrNull(if (position >= 0) position else -position - 1)
        }
    }

    override suspend fun indexesIn(range: IntRange): List<Int> {
        requireActive()
        return indexes.readSession { snapshot ->
            requireAuthority()
            if (range.isEmpty()) return@readSession emptyList()
            require(range.first >= 0) {
                "Index lower bound ${range.first} must be non-negative."
            }
            val firstPosition = snapshot.binarySearch(range.first).let { position ->
                if (position >= 0) position else -position - 1
            }
            if (firstPosition >= snapshot.size) {
                emptyList()
            } else {
                val lastPosition = snapshot.binarySearch(range.last).let { position ->
                    if (position >= 0) position + 1 else -position - 1
                }
                snapshot.subList(firstPosition, lastPosition).toList()
            }
        }
    }

    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> {
        requireActive()
        return indexes.readSession { snapshot ->
            requireAuthority()
            if (range.isEmpty()) return@readSession emptyList()
            require(range.first >= 0) {
                "Index lower bound ${range.first} must be non-negative."
            }
            val firstPosition = snapshot.binarySearch(range.first).let { position ->
                if (position >= 0) position else -position - 1
            }
            val storedIndexes = if (firstPosition >= snapshot.size) {
                emptyList()
            } else {
                val lastPosition = snapshot.binarySearch(range.last).let { position ->
                    if (position >= 0) position + 1 else -position - 1
                }
                snapshot.subList(firstPosition, lastPosition).toList()
            }
            val result = storedIndexes.map { index ->
                index to values.get(index) { delegate.getUnsafe(index) }
            }
            if (!isActive) {
                values.invalidateAll()
                requireActive()
            }
            result
        }
    }

    override suspend fun set(index: Int, value: T) {
        requireActive()
        indexes.writeSession { cache ->
            requireAuthority()
            val latest = cache.lastOrNull() ?: -1
            check(index > latest) {
                "Sparse append-only timeline requires index greater than $latest, got $index."
            }
            try {
                delegate.setUnsafe(index, value)
            } catch (failure: Throwable) {
                // Publication can precede cleanup failure. Fail closed while
                // reconciling, and remain closed if the numbered-record scan
                // fails. Only this successful scan restores cache authority.
                unresolvedFailure = failure
                val resync = withContext(NonCancellable) {
                    withContext(Dispatchers.Default) {
                        try {
                            withTimeout(30.seconds) {
                                runCatching {
                                    val durable = delegate.indexesIn(0..Int.MAX_VALUE)
                                    values.invalidate(index)
                                    cache.clear()
                                    cache.addAll(durable)
                                    mutableLatestIndex.value = durable.lastOrNull() ?: -1
                                    unresolvedFailure = null
                                }
                            }
                        } catch (secondary: Throwable) {
                            Result.failure<Unit>(secondary)
                        }
                    }
                }.exceptionOrNull()
                if (resync != null && resync !== failure) failure.addSuppressed(resync)
                throw failure
            }
            withContext(NonCancellable) {
                if (isActive) {
                    values.put(index, value)
                    if (!isActive) {
                        values.invalidate(index)
                    }
                }
                cache += index
                mutableLatestIndex.value = index
            }
        }
    }

    override suspend fun revert(untilExclusive: Int) {
        requireActive()
        indexes.writeSession { cache ->
            requireAuthority()
            delegate.revert(untilExclusive)
            withContext(NonCancellable) {
                val position = cache.binarySearch(untilExclusive)
                val suffixStart = if (position >= 0) position else -position - 1
                if (suffixStart < cache.size) {
                    values.invalidateAll()
                    cache.subList(suffixStart, cache.size).clear()
                    val previousNonce = mutableCacheNonce.value
                    var nextNonce: Long
                    do {
                        nextNonce = Random.nextLong()
                    } while (nextNonce == previousNonce)
                    mutableCacheNonce.value = nextNonce
                    mutableLatestIndex.value = cache.lastOrNull() ?: -1
                }
            }
        }
    }

    /** Caller holds an indexes read or write session. */
    private suspend fun requireAuthority() {
        requireActive()
        check(unresolvedFailure == null) {
            "Cached timeline authority is uncertain; reopen the owning AgentSession."
        }
    }

    private suspend fun requireActive() {
        check(isActive) { "Cached timeline is closed." }
    }
}

private val CachedValueTtl: Duration = 60.seconds
private const val CacheCleanupKey: Int = -1
