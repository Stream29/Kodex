package io.github.stream29.kodex.agentsession.filesystem

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.SafeRw
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.reactivecircus.cache4k.Cache
import io.github.reactivecircus.cache4k.CacheEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val cachedTokenCount: CachedIndexVersionedImpl<Long>,
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
    override val tokenCount: CachedIndexVersionedImpl<Long>
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

    /** In-memory cache identity; value eviction is not a timeline replacement. */
    override val cacheNonce: StateFlow<Long> = mutableCacheNonce.asStateFlow()

    /** Published tail of this timeline, independent of the Agent's global storage index. */
    override val latestIndex: StateFlow<Int> = mutableLatestIndex.asStateFlow()

    private val indexes = SafeRw<List<Int>, MutableList<Int>>(
        indexes.toMutableList(),
    )
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
        return indexes.readSession { it.lastOrNull() ?: -1 }
    }

    override suspend fun get(index: Int): T {
        requireActive()
        require(index >= 0) { "Index $index must be non-negative." }
        val storedIndex = indexes.readSession { snapshot ->
            val position = snapshot.binarySearch(index)
            val floorPosition = if (position >= 0) position else -position - 2
            snapshot.getOrNull(floorPosition)
                ?: throw IllegalArgumentException("No value is visible at index $index.")
        }
        val value = values.get(storedIndex) { delegate.getUnsafe(storedIndex) }
        if (!isActive) {
            values.invalidate(storedIndex)
            requireActive()
        }
        return value
    }

    override suspend fun getExact(index: Int): T? {
        requireActive()
        require(index >= 0) { "Index $index must be non-negative." }
        val stored = indexes.readSession { snapshot ->
            snapshot.binarySearch(index).takeIf { it >= 0 }?.let { snapshot[it] }
        } ?: return null
        val value = values.get(stored) { delegate.getUnsafe(stored) }
        if (!isActive) {
            values.invalidate(stored)
            requireActive()
        }
        return value
    }

    override suspend fun floorToIndex(index: Int): Int? {
        requireActive()
        return indexes.readSession { snapshot ->
            val position = snapshot.binarySearch(index)
            snapshot.getOrNull(if (position >= 0) position else -position - 2)
        }
    }

    override suspend fun ceilToIndex(index: Int): Int? {
        requireActive()
        return indexes.readSession { snapshot ->
            val position = snapshot.binarySearch(index)
            snapshot.getOrNull(if (position >= 0) position else -position - 1)
        }
    }

    override suspend fun indexesIn(range: IntRange): List<Int> {
        requireActive()
        if (range.isEmpty()) return emptyList()
        require(range.first >= 0) {
            "Index lower bound ${range.first} must be non-negative."
        }
        return indexes.readSession { snapshot ->
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
        if (range.isEmpty()) return emptyList()
        require(range.first >= 0) {
            "Index lower bound ${range.first} must be non-negative."
        }
        val storedIndexes = indexes.readSession { snapshot ->
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
        return storedIndexes.map { index ->
            index to values.get(index) { delegate.getUnsafe(index) }
        }
    }

    override suspend fun set(index: Int, value: T) {
        requireActive()
        indexes.readSession { cache ->
            val latest = cache.lastOrNull() ?: -1
            check(index > latest) {
                "Sparse append-only timeline requires index greater than $latest, got $index."
            }
        }
        delegate.setUnsafe(index, value)
        withContext(NonCancellable) {
            indexes.writeSession { cache ->
                val latest = cache.lastOrNull() ?: -1
                check(index > latest) {
                    "Cached timeline changed while appending index $index."
                }
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
        delegate.revert(untilExclusive)
        withContext(NonCancellable) {
            indexes.writeSession { cache ->
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

    private suspend fun requireActive() {
        check(isActive) { "Cached timeline is closed." }
    }
}

private val CachedValueTtl: Duration = 60.seconds
private const val CacheCleanupKey: Int = -1
