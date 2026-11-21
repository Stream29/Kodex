package io.github.stream29.kodex.app.sessioncatalog

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupReads
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupSummary
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Catalog-owned data and choices; an observing panel lends the subscription lifetime. */
internal class SessionGroupFeed(private val reads: SessionGroupReads) {
    private val mounted = Mutex()
    private val mutableState = MutableStateFlow(SessionGroupsState())
    private val refreshEpoch = MutableStateFlow(0L)
    val state: StateFlow<SessionGroupsState> = mutableState.asStateFlow()

    fun refresh() = refreshEpoch.update { it + 1 }

    fun toggleGroup(cwd: String?, focus: SessionGroupFocus?) = mutableState.update { state ->
        if (state.groups.orEmpty().none { it.cwd == cwd }) state
        else state.copy(manualExpansion = state.manualExpansion + (cwd to !state.isExpanded(cwd, focus)))
    }

    fun toggleArchived(cwd: String?) = mutableState.update { state ->
        if (state.groups.orEmpty().none { it.cwd == cwd }) state
        else state.copy(showArchivedGroups = state.showArchivedGroups.toggled(cwd))
    }

    fun toggleLimit(cwd: String?) = mutableState.update { state ->
        if (state.groups.orEmpty().none { it.cwd == cwd }) state
        else state.copy(expandedLimitGroups = state.expandedLimitGroups.toggled(cwd))
    }

    suspend fun observe(focus: Flow<SessionGroupFocus?>, onFailure: (Throwable) -> Unit): Unit =
        mounted.withLock {
            try {
                coroutineScope {
                    launch { observeSummaries(onFailure) }
                    val jobs = mutableMapOf<String?, Job>()
                    val expansion = state.distinctUntilChanged { old, new ->
                        old.groups === new.groups && old.manualExpansion === new.manualExpansion
                    }
                    combine(expansion, focus) { state, focused ->
                        state.groups.orEmpty().filter { state.isExpanded(it.cwd, focused) }.map { it.cwd }.toSet()
                    }.distinctUntilChanged().collect { expanded ->
                        (jobs.keys - expanded).forEach { cwd ->
                            jobs.remove(cwd)?.cancelAndJoin()
                            mutableState.update { it.copy(pages = it.pages - cwd) }
                        }
                        for (cwd in expanded) {
                            if (cwd !in jobs) jobs[cwd] = launch { observePage(cwd, onFailure) }
                        }
                    }
                }
            } finally {
                mutableState.update { it.copy(groups = null, pages = emptyMap()) }
            }
        }

    private suspend fun observeSummaries(onFailure: (Throwable) -> Unit): Unit = coroutineScope {
        val invalidations = invalidations(reads::getSessionGroupsNonceFlow, onFailure)
        invalidations.conflate().collect {
            report(onFailure) {
                val groups = reads.listSessionGroups().sortedWith(GroupOrder)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(groups = groups) }
            }
        }
    }

    private suspend fun observePage(cwd: String?, onFailure: (Throwable) -> Unit): Unit = coroutineScope {
        val invalidations = invalidations({ reads.getSessionGroupNonceFlow(cwd) }, onFailure)
        state.map { it.pageQuery(cwd) }.distinctUntilChanged().collectLatest { query ->
            // Only a change in query parameters cancels a read. A burst of nonces
            // conflates to one follow-up read rather than starving an ongoing read.
            invalidations.conflate().collect {
                report(onFailure) {
                    val page = reads.listSessionsInGroup(cwd, query.includeArchived, query.limit)
                    check(page.cwd == cwd) { "The catalog returned a page for a different group." }
                    currentCoroutineContext().ensureActive()
                    mutableState.update { state ->
                        if (state.pageQuery(cwd) == query) state.copy(pages = state.pages + (cwd to page))
                        else state
                    }
                }
            }
        }
    }

    private fun CoroutineScope.invalidations(
        source: () -> Flow<Long>, onFailure: (Throwable) -> Unit,
    ): Flow<Invalidation> {
        val initialEpoch = refreshEpoch.value
        val nonce = MutableStateFlow<Long?>(null)
        launch { report(onFailure) { source().collect { nonce.value = it } } }
        return combine(nonce, refreshEpoch) { current, epoch ->
            if (current != null || epoch != initialEpoch) Invalidation(current, epoch) else null
        }.filterNotNull()
    }

    private suspend inline fun report(onFailure: (Throwable) -> Unit, work: () -> Unit) {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }
}

private data class Invalidation(val nonce: Long?, val refreshEpoch: Long)
private data class PageQuery(val includeArchived: Boolean, val limit: Int?)

private fun SessionGroupsState.pageQuery(cwd: String?) =
    PageQuery(cwd in showArchivedGroups, if (cwd in expandedLimitGroups) null else 5)

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

private val GroupOrder = compareBy<SessionGroupSummary> { it.cwd == null }
    .thenByDescending { it.latestUpdatedAt }
    .thenBy { it.cwd }
