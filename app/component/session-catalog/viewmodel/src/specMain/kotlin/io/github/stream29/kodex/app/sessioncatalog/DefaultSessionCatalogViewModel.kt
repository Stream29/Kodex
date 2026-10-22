@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.sessioncatalog

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDeleteHandle
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogInteractions
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Owns catalog presentation without knowing its RPC or persistence protocol. */
public expect class DefaultSessionCatalogViewModel(
    scope: CoroutineScope,
    dependencies: SessionCatalogDependencies,
    interactions: SessionCatalogInteractions? = null,
) : SessionCatalogViewModel {
    override val state: StateFlow<SessionCatalogState>
    override val deleteTarget: StateFlow<SessionCatalogDeleteHandle?>
    override suspend fun requestOpen(entry: SessionCatalogEntry): Unit
    override fun requestDelete(entry: SessionCatalogEntry): SessionCatalogDeleteHandle?
    override fun dismissDelete(handle: SessionCatalogDeleteHandle): Unit
    override fun dismiss(): Unit
    override suspend fun refresh(): Unit
    override suspend fun setShowArchived(showArchived: Boolean): Unit
    override suspend fun readCreatedAt(sessionIndex: Int): Instant?
    override suspend fun readUpdatedAt(sessionIndex: Int): Instant?
    override suspend fun archive(sessionIndex: Int): Unit
    override suspend fun unarchive(sessionIndex: Int): Unit
    override suspend fun fork(sessionIndex: Int): Int
    override suspend fun delete(sessionIndex: Int): Boolean
    override fun close(): Unit
}
