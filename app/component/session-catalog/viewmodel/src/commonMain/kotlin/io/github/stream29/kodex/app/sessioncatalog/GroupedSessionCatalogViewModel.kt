package io.github.stream29.kodex.app.sessioncatalog.contract

import io.github.stream29.kodex.app.sessioncatalog.SessionGroupFeed
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public actual fun createSessionGroupsViewModel(
    scope: CoroutineScope, reads: SessionGroupReads, actions: SessionGroupActions,
): SessionGroupsViewModel = GroupedSessionCatalogViewModel(scope, reads, actions)

private class GroupedSessionCatalogViewModel(
    scope: CoroutineScope, reads: SessionGroupReads, private val actions: SessionGroupActions,
) : SessionGroupsViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val dispatcher = scope.coroutineContext.minusKey(Job)
    private val commands = Mutex()
    private val feed = SessionGroupFeed(reads)
    override val state = feed.state
    private val pendingDelete = MutableStateFlow<SessionCatalogDeleteHandle?>(null)
    override val deleteTarget = pendingDelete.asStateFlow()

    override suspend fun observeGroups(focus: Flow<SessionGroupFocus?>, onFailure: (Throwable) -> Unit) = owned {
        feed.observe(focus, onFailure)
    }

    override fun refresh() = feed.refresh()
    override fun toggleGroup(cwd: String?, focus: SessionGroupFocus?) = feed.toggleGroup(cwd, focus)
    override fun toggleArchived(cwd: String?) = feed.toggleArchived(cwd)
    override fun toggleLimit(cwd: String?) = feed.toggleLimit(cwd)

    override suspend fun requestOpen(target: SessionGroupTarget) = command {
        if (contains(target)) actions.open(target.entry)
    }

    override suspend fun requestRename(target: SessionGroupTarget) = command {
        if (contains(target)) actions.rename(target.entry)
    }

    override suspend fun requestArchive(target: SessionGroupTarget) = command {
        if (contains(target)) actions.setArchived(target.entry, archived = !target.entry.archived)
    }

    override fun requestDelete(target: SessionGroupTarget): SessionCatalogDeleteHandle? {
        owner.ensureActive()
        if (!contains(target)) return null
        pendingDelete.value?.let(::dismissDelete)
        lateinit var handle: SessionCatalogDeleteHandle
        val child = createSessionDeleteViewModel(
            target.entry.sessionIndex,
            target.entry.name,
            SessionDeleteDependencies {
                command {
                    check(pendingDelete.value === handle) { "The catalog Delete handle is no longer active." }
                    val deleted = actions.delete(target.entry)
                    if (deleted) dismissDelete(handle)
                    deleted
                }
            },
        )
        handle = SessionCatalogDeleteHandle(target.entry, child)
        pendingDelete.value = handle
        return handle
    }

    override fun dismissDelete(handle: SessionCatalogDeleteHandle) {
        if (pendingDelete.value !== handle) return
        pendingDelete.value = null
        handle.viewModel.close()
    }

    private fun contains(target: SessionGroupTarget): Boolean {
        val snapshot = state.value
        return target.entry in snapshot.visibleEntries(target.cwd)
    }

    private suspend fun <T> command(action: suspend () -> T): T = owned {
        commands.withLock { action() }
    }

    private suspend fun <T> owned(action: suspend () -> T): T = coroutineScope {
        val cancellation = owner.invokeOnCompletion { cancel() }
        try {
            withContext(dispatcher) { owner.ensureActive(); action() }
        } finally {
            cancellation.dispose()
        }
    }

    override fun close() {
        pendingDelete.value?.let(::dismissDelete)
        owner.cancel()
    }
}
