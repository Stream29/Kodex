package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/** Lazy full catalog snapshot. Reading dates neither activates Sessions nor touches local files. */
public class RpcSessionCatalog(
    scope: CoroutineScope,
    private val global: GlobalRpc,
    private val deleteSession: suspend (Int) -> Boolean = global::deleteSession,
    private val forkSession: suspend (Int) -> Int = global::forkSession,
) : SessionCatalogViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val localContext = scope.coroutineContext + owner
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SessionCatalogState>(SessionCatalogState.Unloaded)
    override val state: StateFlow<SessionCatalogState> = mutableState.asStateFlow()

    override suspend fun refresh(): Unit = operation { reload(state.value.showArchived) }
    override suspend fun setShowArchived(showArchived: Boolean): Unit = operation {
        if (state.value.showArchived != showArchived) reload(showArchived)
    }
    override suspend fun readCreatedAt(sessionIndex: Int): Instant? = operation {
        state.value.sessions.first { it.sessionIndex == sessionIndex }.createdAt
    }
    override suspend fun readUpdatedAt(sessionIndex: Int): Instant? = operation {
        state.value.sessions.first { it.sessionIndex == sessionIndex }.updatedAt
    }
    override suspend fun archive(sessionIndex: Int): Unit = operation {
        global.archiveSession(sessionIndex)
        reload(state.value.showArchived)
    }
    override suspend fun unarchive(sessionIndex: Int): Unit = operation {
        global.unarchiveSession(sessionIndex)
        reload(state.value.showArchived)
    }
    override suspend fun fork(sessionIndex: Int): Int = operation {
        forkSession(sessionIndex).also { reload(state.value.showArchived) }
    }
    override suspend fun delete(sessionIndex: Int): Boolean = operation {
        deleteSession(sessionIndex).also {
            reload(state.value.showArchived)
        }
    }

    private suspend fun reload(archived: Boolean) {
        val previous = state.value
        mutableState.value = SessionCatalogState.Loading(archived, previous.sessions)
        try {
            val entries = global.getSessionCatalog(archived)
            owner.ensureActive()
            mutableState.value = SessionCatalogState.Loaded(archived, entries)
        } catch (failure: Throwable) {
            mutableState.value = previous
            throw failure
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T = withContext(localContext) {
        mutex.withLock { owner.ensureActive(); action() }
    }
    override fun close() { owner.cancel() }
}
