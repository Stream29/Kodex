package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The exact directory and persisted row captured when the action was requested. */
public data class SessionGroupTarget(public val cwd: String?, public val entry: SessionCatalogEntry)

/** Application navigation and existing Session management commands. */
public interface SessionGroupActions {
    public suspend fun open(entry: SessionCatalogEntry)
    public suspend fun rename(entry: SessionCatalogEntry)
    public suspend fun setArchived(entry: SessionCatalogEntry, archived: Boolean)
    public suspend fun delete(entry: SessionCatalogEntry): Boolean
}

/** Application-owned; observing one mounted panel is the only source of group subscriptions. */
public interface SessionGroupsViewModel : AutoCloseable {
    public val state: StateFlow<SessionGroupsState>
    public val deleteTarget: StateFlow<SessionCatalogDeleteHandle?>
    public suspend fun observeGroups(focus: Flow<SessionGroupFocus?>, onFailure: (Throwable) -> Unit)
    public fun refresh()
    public fun toggleGroup(cwd: String?, focus: SessionGroupFocus?)
    public fun toggleArchived(cwd: String?)
    public fun toggleLimit(cwd: String?)
    public suspend fun requestOpen(target: SessionGroupTarget)
    public suspend fun requestRename(target: SessionGroupTarget)
    public suspend fun requestArchive(target: SessionGroupTarget)
    public fun requestDelete(target: SessionGroupTarget): SessionCatalogDeleteHandle?
    public fun dismissDelete(handle: SessionCatalogDeleteHandle)
    override fun close()
}

public expect fun createSessionGroupsViewModel(
    scope: CoroutineScope, reads: SessionGroupReads, actions: SessionGroupActions,
): SessionGroupsViewModel
