package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.rpc.contract.GlobalRpc

/** Adapts Global RPC to the component's backend port without opening Session runtimes. */
public class RpcSessionCatalogDependencies(
    private val global: GlobalRpc,
    private val deleteSession: suspend (Int) -> Boolean = global::deleteSession,
    private val forkSession: suspend (Int) -> Int = global::forkSession,
) : SessionCatalogDependencies {
    override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry> =
        global.getSessionCatalog(showArchived)

    override suspend fun archive(sessionIndex: Int): Unit =
        global.archiveSession(sessionIndex)

    override suspend fun unarchive(sessionIndex: Int): Unit =
        global.unarchiveSession(sessionIndex)

    override suspend fun fork(sessionIndex: Int): Int =
        forkSession(sessionIndex)

    override suspend fun delete(sessionIndex: Int): Boolean =
        deleteSession(sessionIndex)
}
