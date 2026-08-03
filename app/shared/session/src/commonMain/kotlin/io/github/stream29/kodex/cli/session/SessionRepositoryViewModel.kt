package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentstorage.contract.forkTo
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/** Repository-level CLI state. A session entry is one root Agent tree. */
public data class SessionRepositoryViewState(
    public val sessions: List<RootSessionEntry> = emptyList(),
    public val selectedSessionIndex: Int? = null,
)

public data class RootSessionEntry(
    public val sessionIndex: Int,
    public val viewModel: RootSessionViewModel?,
    public val selected: Boolean,
    /** Current root Agent title read from the lightweight persisted catalog. */
    public val threadName: String? = null,
    /** Latest root activity read from the lightweight persisted catalog. */
    public val lastActivityAt: Instant? = null,
)

/**
 * Frontend owner for root session trees in a [KodexSessionRepository].
 *
 * It only performs repository operations and assembles session VMs. Agent execution remains
 * owned by each session's runtime, while root initialization stays an explicit caller action.
 */
public class SessionRepositoryViewModel internal constructor(
    private val repository: KodexSessionRepository,
    private val scope: CoroutineScope,
    private val automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
) : AutoCloseable {
    private val rootViewModels = linkedMapOf<Int, RootSessionViewModel>()
    private val mutableState = MutableStateFlow(SessionRepositoryViewState())

    public val state: StateFlow<SessionRepositoryViewState> = mutableState.asStateFlow()

    /** Synchronizes the visible root-session list without opening previously unopened roots. */
    public suspend fun refresh() {
        val entries = repository.listEntries()
        val indexes = entries.map(KodexSessionEntry::entryIndex)
        rootViewModels.entries.removeAll { (index, viewModel) ->
            if (index in indexes) {
                false
            } else {
                viewModel.close()
                true
            }
        }
        val selected = mutableState.value.selectedSessionIndex?.takeIf { it in indexes }
        publish(entries, selected)
    }

    /** Opens one root tree and makes it selected. Repeated calls reuse the repository session and VM. */
    public suspend fun open(sessionIndex: Int): RootSessionViewModel {
        require(sessionIndex in repository.list()) { "No root session at index $sessionIndex." }
        val viewModel = rootViewModels.getOrPut(sessionIndex) {
            scope.RootSessionViewModel(
                rootSession = repository.open(sessionIndex),
                automaticTitleConfiguration = automaticTitleConfiguration,
            )
        }
        viewModel.refresh()
        publish(repository.listEntries(), selectedSessionIndex = sessionIndex)
        return viewModel
    }

    /** Clears frontend selection while retaining every already-opened root tree. */
    public suspend fun clearSelection() {
        publish(repository.listEntries(), selectedSessionIndex = null)
    }

    /** Creates, initializes, opens, and selects one root session. */
    public suspend fun create(
        initialSettings: (sessionIndex: Int) -> KodexAgentSettings,
    ): RootSessionViewModel {
        val index = repository.create()
        try {
            repository.open(index).runtime.modify { storage ->
                storage.initialize(initialSettings(index))
            }
            return open(index)
        } catch (failure: Throwable) {
            repository.delete(index)
            throw failure
        }
    }

    /** Releases one opened frontend tree without deleting its persisted root session. */
    public suspend fun close(sessionIndex: Int) {
        rootViewModels.remove(sessionIndex)?.close()
        val entries = repository.listEntries()
        val indexes = entries.map(KodexSessionEntry::entryIndex)
        val selected = mutableState.value.selectedSessionIndex
            ?.takeIf { index -> index != sessionIndex && index in indexes }
        publish(entries, selected)
    }

    /** Deletes one root tree and releases its frontend VM. */
    public suspend fun delete(sessionIndex: Int) {
        rootViewModels.remove(sessionIndex)?.close()
        repository.delete(sessionIndex)
        val entries = repository.listEntries()
        val indexes = entries.map(KodexSessionEntry::entryIndex)
        publish(entries, mutableState.value.selectedSessionIndex?.takeIf { it in indexes })
    }

    /**
     * Forks a root tree's storage at [untilExclusive] into a new root session.
     *
     * The boundary is supplied by the caller because turn/checkpoint UI policy is not a repository
     * concern. Descendant trees are intentionally not copied: storage fork semantics are per Agent.
     */
    public suspend fun fork(
        sourceSessionIndex: Int,
        untilExclusive: Int,
    ): RootSessionViewModel {
        val source = repository.open(sourceSessionIndex)
        val targetIndex = repository.create()
        try {
            val target = repository.open(targetIndex)
            target.runtime.modify { targetStorage ->
                source.runtime.storage.forkTo(untilExclusive, targetStorage)
            }
            return open(targetIndex)
        } catch (failure: Throwable) {
            repository.delete(targetIndex)
            throw failure
        }
    }

    override fun close() {
        rootViewModels.values.forEach(RootSessionViewModel::close)
        rootViewModels.clear()
        scope.cancel()
    }

    private fun publish(entries: List<KodexSessionEntry>, selectedSessionIndex: Int?) {
        mutableState.value = SessionRepositoryViewState(
            sessions = entries.sortedWith(
                compareByDescending<KodexSessionEntry> { entry -> entry.lastActivityAt }
                    .thenByDescending { entry -> entry.entryIndex },
            ).map { entry ->
                RootSessionEntry(
                    sessionIndex = entry.entryIndex,
                    viewModel = rootViewModels[entry.entryIndex],
                    selected = entry.entryIndex == selectedSessionIndex,
                    threadName = entry.threadName,
                    lastActivityAt = entry.lastActivityAt,
                )
            },
            selectedSessionIndex = selectedSessionIndex,
        )
    }
}

/** Creates a repository VM whose root-tree VMs are children of this scope. */
public fun CoroutineScope.SessionRepositoryViewModel(
    repository: KodexSessionRepository,
    automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
): SessionRepositoryViewModel =
    SessionRepositoryViewModel(
        repository = repository,
        scope = supervisorChildScope(),
        automaticTitleConfiguration = automaticTitleConfiguration,
    )
