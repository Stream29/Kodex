@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.rpc.contract.GlobalRpc

public actual class RpcSessionCatalogDependencies actual constructor(
    private val global: GlobalRpc,
    private val deleteSession: suspend (Int) -> Boolean,
    private val forkSession: suspend (Int) -> Int,
) : SessionCatalogDependencies {
    actual override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry> =
        global.getSessionCatalog(showArchived)

    actual override suspend fun archive(sessionIndex: Int): Unit =
        global.archiveSession(sessionIndex)

    actual override suspend fun unarchive(sessionIndex: Int): Unit =
        global.unarchiveSession(sessionIndex)

    actual override suspend fun fork(sessionIndex: Int): Int =
        forkSession(sessionIndex)

    actual override suspend fun delete(sessionIndex: Int): Boolean =
        deleteSession(sessionIndex)
}
