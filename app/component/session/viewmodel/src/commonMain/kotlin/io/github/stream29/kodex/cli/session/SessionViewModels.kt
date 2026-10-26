@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.app.session.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public actual class DefaultPersistedSessionViewModelRegistry actual constructor(
    public actual val views: PersistedSessionViews,
) : PersistedSessionViewModelRegistry {
    private val mutex = Mutex()
    private val handles = mutableMapOf<Int, OwnedPersistedSessionViewModel>()
    private var closed = false

    actual override suspend fun open(sessionIndex: Int): PersistedSessionViewModel = mutex.withLock {
        check(!closed)
        views.unarchive(sessionIndex)
        handles[sessionIndex] ?: views.open(sessionIndex).also { handles[sessionIndex] = it }
    }
    actual override suspend fun release(sessionIndex: Int) {
        val handle = mutex.withLock {
            val removed = handles.remove(sessionIndex)
            removed?.close()
            // Keep admission serialized until the old raw view is removed. An opener
            // must not publish a replacement that this release could address by index.
            if (removed == null) views.release(sessionIndex) else views.release(removed)
            removed
        }
        handle?.join()
    }
    actual override suspend fun archive(sessionIndex: Int): Unit = views.archive(sessionIndex)
    actual override suspend fun unarchive(sessionIndex: Int): Unit = views.unarchive(sessionIndex)
    actual override suspend fun fork(sessionIndex: Int): Int = views.fork(sessionIndex)
    actual override suspend fun delete(sessionIndex: Int): Boolean =
        views.delete(sessionIndex).also { if (it) release(sessionIndex) }
    actual override suspend fun shutdown() {
        val previous = mutex.withLock {
            closed = true
            handles.values.toList().also { handles.clear(); it.forEach(OwnedPersistedSessionViewModel::close) }
        }
        views.close()
        withContext(NonCancellable) { views.join(); previous.forEach { it.join() } }
    }
}
