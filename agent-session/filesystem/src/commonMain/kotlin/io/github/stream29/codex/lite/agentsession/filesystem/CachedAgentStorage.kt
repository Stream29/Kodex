package io.github.stream29.codex.lite.agentsession.filesystem

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.codex.lite.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.codex.lite.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.utils.SafeRw
import io.github.reactivecircus.cache4k.Cache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal suspend fun FileSystemAgentStorage.cached(
    ownerScope: CoroutineScope,
    valueCacheSize: Int,
): CachedAgentStorage {
    require(valueCacheSize > 0) { "Value cache size must be positive." }
    check(ownerScope.isActive) { "AgentSession storage is closed." }
    return CachedAgentStorage(
        ownerScope = ownerScope,
        storageId = id,
        cachedHistory = history.cached(ownerScope, valueCacheSize),
        cachedCompaction = compaction.cached(ownerScope, valueCacheSize),
        cachedSettings = settings.cached(ownerScope, valueCacheSize),
        cachedTimestamp = timestamp.cached(ownerScope, valueCacheSize),
        cachedTokenCount = tokenCount.cached(ownerScope, valueCacheSize),
        cachedStable = stable.cached(ownerScope, valueCacheSize),
        cachedUnstable = unstable.cached(ownerScope, valueCacheSize),
    )
}

/** Session-owned cache over one filesystem AgentStorage. */
internal class CachedAgentStorage internal constructor(
    private val ownerScope: CoroutineScope,
    private val storageId: String,
    private val cachedHistory: MutableIndexVersioned<ResponseItem.HistoryItem>,
    private val cachedCompaction: MutableIndexVersioned<CompactionCheckpoint>,
    private val cachedSettings: MutableIndexVersioned<CodexAgentSettings>,
    private val cachedTimestamp: MutableIndexVersioned<kotlin.time.Instant>,
    private val cachedTokenCount: MutableIndexVersioned<Long>,
    private val cachedStable: MutableIndexVersioned<StableCleanEvent>,
    private val cachedUnstable: MutableIndexVersioned<List<PendingToolEvent>>,
) : MutableCodexAgentStorage {
    override val id: String
        get() {
            requireActive()
            return storageId
        }
    override val history: MutableIndexVersioned<ResponseItem.HistoryItem>
        get() {
            requireActive()
            return cachedHistory
        }
    override val compaction: MutableIndexVersioned<CompactionCheckpoint>
        get() {
            requireActive()
            return cachedCompaction
        }
    override val settings: MutableIndexVersioned<CodexAgentSettings>
        get() {
            requireActive()
            return cachedSettings
        }
    override val timestamp: MutableIndexVersioned<kotlin.time.Instant>
        get() {
            requireActive()
            return cachedTimestamp
        }
    override val tokenCount: MutableIndexVersioned<Long>
        get() {
            requireActive()
            return cachedTokenCount
        }
    override val stable: MutableIndexVersioned<StableCleanEvent>
        get() {
            requireActive()
            return cachedStable
        }
    override val unstable: MutableIndexVersioned<List<PendingToolEvent>>
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
): CachedIndexVersioned<T> {
    return CachedIndexVersioned(
        ownerScope = ownerScope,
        delegate = this,
        valueCacheSize = valueCacheSize,
        indexes = storedIndexes(),
    )
}

private class CachedIndexVersioned<T : Any>(
    private val ownerScope: CoroutineScope,
    private val delegate: FileSystemIndexVersioned<T>,
    private val valueCacheSize: Int,
    indexes: List<Int>,
) : MutableIndexVersioned<T> {
    private val indexes = SafeRw<List<Int>, MutableList<Int>>(
        indexes.toMutableList(),
    )
    private val values = Cache.Builder<Int, T>().maximumCacheSize(valueCacheSize.toLong()).build()

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
        return values.get(storedIndex) { delegate.getUnsafe(storedIndex) }
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
                values.put(index, value)
                cache += index
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
                }
            }
        }
    }

    private suspend fun requireActive() {
        check(ownerScope.isActive) { "Cached timeline is closed." }
    }
}
