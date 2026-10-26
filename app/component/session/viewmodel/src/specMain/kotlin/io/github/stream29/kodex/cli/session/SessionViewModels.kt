@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.app.session.contract.*

/** Frontend handles only. The real repository and its resource graph never enter this module. */
public expect class DefaultPersistedSessionViewModelRegistry(
    views: PersistedSessionViews,
) : PersistedSessionViewModelRegistry {
    public val views: PersistedSessionViews
    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel
    override suspend fun release(sessionIndex: Int)
    override suspend fun archive(sessionIndex: Int)
    override suspend fun unarchive(sessionIndex: Int)
    override suspend fun fork(sessionIndex: Int): Int
    override suspend fun delete(sessionIndex: Int): Boolean
    override suspend fun shutdown()
}


/** A registry-owned child. Joining waits for its presentation owner and read binding cleanup. */
public interface OwnedPersistedSessionViewModel : PersistedSessionViewModel {
    public suspend fun join()
}

/**
 * Borrowed session presentation access. Release by handle must only remove that exact binding,
 * never a replacement with the same index. Commands preserve backend admission/cancellation;
 * closing this port releases frontend observations, not backend work.
 */
public interface PersistedSessionViews : AutoCloseable {
    public suspend fun open(sessionIndex: Int): OwnedPersistedSessionViewModel
    public suspend fun release(sessionIndex: Int)
    public suspend fun release(handle: OwnedPersistedSessionViewModel)
    public suspend fun archive(sessionIndex: Int)
    public suspend fun unarchive(sessionIndex: Int)
    public suspend fun fork(sessionIndex: Int): Int
    public suspend fun delete(sessionIndex: Int): Boolean
    override fun close()
    public suspend fun join()
}
