package io.github.stream29.kodex.app.sessioncatalog

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDeleteHandle
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogInteractions
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Instant

/** Owns catalog presentation without knowing its RPC or persistence protocol. */
public class DefaultSessionCatalogViewModel(
    scope: CoroutineScope,
    private val dependencies: SessionCatalogDependencies,
    private val interactions: SessionCatalogInteractions? = null,
) : SessionCatalogViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val localContext = scope.coroutineContext + owner
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SessionCatalogState>(SessionCatalogState.Unloaded)
    override val state: StateFlow<SessionCatalogState> = mutableState.asStateFlow()
    private val mutableDeleteTarget = MutableStateFlow<SessionCatalogDeleteHandle?>(null)
    override val deleteTarget: StateFlow<SessionCatalogDeleteHandle?> = mutableDeleteTarget.asStateFlow()

    private fun containsCapturedRow(entry: SessionCatalogEntry): Boolean =
        state.value is SessionCatalogState.Loaded && state.value.sessions.any { it == entry }

    override suspend fun requestOpen(entry: SessionCatalogEntry) {
        val port = operation {
            if (!containsCapturedRow(entry)) return@operation null
            val captured = checkNotNull(interactions) { "Catalog navigation ports were not supplied." }
            captured.openSession(entry.sessionIndex)
            coroutineContext.ensureActive()
            owner.ensureActive()
            captured
        }
        // Dismissal closes this catalog. Release the operation's cancellation linkage first so
        // a successful navigation does not cancel its caller merely by closing its own popup.
        port?.dismissPopup()
    }

    override fun requestDelete(entry: SessionCatalogEntry): SessionCatalogDeleteHandle? {
        owner.ensureActive()
        if (!containsCapturedRow(entry)) return null
        mutableDeleteTarget.value?.let(::dismissDelete)
        lateinit var handle: SessionCatalogDeleteHandle
        val child = createSessionDeleteViewModel(
            entry.sessionIndex,
            entry.threadName,
            SessionDeleteDependencies { sessionIndex ->
                operation {
                    check(mutableDeleteTarget.value === handle) { "Catalog Delete handle is stale." }
                    val deleted = dependencies.delete(sessionIndex)
                    reload(state.value.showArchived)
                    if (deleted) dismissDelete(handle)
                    deleted
                }
            },
        )
        handle = SessionCatalogDeleteHandle(entry, child)
        mutableDeleteTarget.value = handle
        return handle
    }

    override fun dismissDelete(handle: SessionCatalogDeleteHandle) {
        if (mutableDeleteTarget.value !== handle) return
        mutableDeleteTarget.value = null
        handle.viewModel.close()
    }

    override fun dismiss() {
        owner.ensureActive()
        checkNotNull(interactions) { "Catalog navigation ports were not supplied." }.dismissPopup()
    }

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
        dependencies.archive(sessionIndex)
        reload(state.value.showArchived)
    }
    override suspend fun unarchive(sessionIndex: Int): Unit = operation {
        dependencies.unarchive(sessionIndex)
        reload(state.value.showArchived)
    }
    override suspend fun fork(sessionIndex: Int): Int = operation {
        dependencies.fork(sessionIndex).also { reload(state.value.showArchived) }
    }
    override suspend fun delete(sessionIndex: Int): Boolean = operation {
        dependencies.delete(sessionIndex).also { reload(state.value.showArchived) }
    }

    private suspend fun reload(archived: Boolean) {
        val previous = state.value
        mutableState.value = SessionCatalogState.Loading(archived, previous.sessions)
        try {
            val entries = dependencies.load(archived)
            coroutineContext.ensureActive()
            owner.ensureActive()
            mutableState.value = SessionCatalogState.Loaded(archived, entries)
        } catch (failure: Throwable) {
            mutableState.value = previous
            throw failure
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T = coroutineScope {
        // Retain the caller Job while also cancelling it when this catalog closes.
        val cancellation = owner.invokeOnCompletion { cancel() }
        try {
            withContext(localContext.minusKey(Job)) {
                mutex.withLock { owner.ensureActive(); action() }
            }
        } finally {
            cancellation.dispose()
        }
    }

    override fun close() {
        mutableDeleteTarget.value?.let(::dismissDelete)
        owner.cancel()
    }
}
