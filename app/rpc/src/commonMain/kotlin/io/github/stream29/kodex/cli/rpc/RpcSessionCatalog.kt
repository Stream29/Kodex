@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.application.contract.ApplicationSessionCatalog
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound

public actual class RpcSessionCatalogDependencies actual constructor(
    private val global: GlobalRpc,
    private val deleteSession: suspend (Int) -> Boolean,
) : SessionCatalogDependencies {
    actual override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry> =
        global.getSessionCatalog(showArchived)

    actual override suspend fun archive(sessionIndex: Int): Unit =
        global.archiveSession(sessionIndex)

    actual override suspend fun unarchive(sessionIndex: Int): Unit =
        global.unarchiveSession(sessionIndex)

    actual override suspend fun delete(sessionIndex: Int): Boolean =
        deleteSession(sessionIndex)
}

internal class RpcApplicationSessionCatalog(
    private val services: RpcServices, private val views: RpcSessionViews,
) : ApplicationSessionCatalog {
    override fun getSessionGroupsNonceFlow() = services.global.getSessionGroupsNonceFlow()
    override suspend fun listSessionGroups() = services.global.listSessionGroups()
    override fun getSessionGroupNonceFlow(cwd: String?) = services.global.getSessionGroupNonceFlow(cwd)
    override suspend fun listSessionsInGroup(cwd: String?, includeArchived: Boolean, limit: Int?) =
        services.global.listSessionsInGroup(cwd, includeArchived, limit)

    override suspend fun isCurrentRoot(entry: SessionCatalogEntry): Boolean = try {
        services.global.getRootAgentId(entry.sessionIndex) == entry.rootAgentId
    } catch (_: SessionNotFound) {
        false
    }

    override suspend fun open(entry: SessionCatalogEntry) =
        views.openCatalogEntry(entry.sessionIndex, entry.rootAgentId)

    override suspend fun rename(entry: SessionCatalogEntry, name: String) {
        check(isCurrentRoot(entry)) { "The original Session no longer exists." }
        val id = entry.rootAgentId
        val runtime = services.runtime
        runtime.keepAgentAlive(id)
        val settings = runtime.getLastCommitsIn(
            id, runtime.getCacheNonce(id), 0, Int.MAX_VALUE, setOf(AgentCommitType.Settings), 1,
        ).single().commit as KodexAgentSettings
        check(runtime.compareAndSetSettings(id, settings, settings.copy(name = name))) {
            "Session settings changed while renaming. Try again."
        }
    }
}
