package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstorage.contract.forkTo
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.PersistedAgentMaterializationState
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionNotification
import io.github.stream29.kodex.app.session.contract.PersistedSessionNotificationLevel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.session.contract.PersistedSessionSummaryState
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyNode
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

/** Repository operations shared by persisted Session, catalog, and draft factories. */
@Factory(binds = [PersistedSessionViewModelRegistry::class])
public class DefaultPersistedSessionViewModelRegistry(
    @InjectedParam private val repository: KodexSessionRepository,
    @InjectedParam private val scope: CoroutineScope,
    @InjectedParam private val agentFactory: PersistedSessionAgentViewModelFactory,
) : PersistedSessionViewModelRegistry {
    private val mutex = Mutex()
    private val opened = linkedMapOf<Int, PersistedSessionViewModelImpl>()

    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel = mutex.withLock {
        require(sessionIndex in repository.list()) {
            "No persisted Session at index $sessionIndex."
        }
        opened[sessionIndex] ?: createOpened(sessionIndex).also {
            opened[sessionIndex] = it
        }
    }

    override suspend fun create(
        initialSettings: (sessionIndex: Int) -> KodexAgentSettings,
    ): PersistedSessionViewModel {
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

    override suspend fun delete(sessionIndex: Int): Boolean = mutex.withLock {
        if (sessionIndex !in repository.list()) return@withLock false
        opened.remove(sessionIndex)?.shutdown()
        repository.delete(sessionIndex)
        true
    }

    /**
     * Removes a Session created by a failed draft materialization.
     *
     * This is intentionally an implementation-layer rollback boundary rather
     * than part of the frontend Session contract.
     */
    override suspend fun rollbackCreated(sessionIndex: Int) {
        mutex.withLock {
            opened.remove(sessionIndex)?.shutdown()
            if (sessionIndex in repository.list()) repository.delete(sessionIndex)
        }
    }

    override suspend fun release(sessionIndex: Int): Unit = mutex.withLock {
        opened.remove(sessionIndex)?.shutdown()
    }

    override suspend fun shutdown(): Unit = mutex.withLock {
        opened.values.toList().forEach { it.shutdown() }
        opened.clear()
    }

    private suspend fun createOpened(index: Int): PersistedSessionViewModelImpl =
        PersistedSessionViewModelImpl(
            sessionIndex = index,
            rootSession = repository.open(index),
            repository = repository,
            ownerScope = scope.supervisorChildScope(),
            agentFactory = agentFactory,
        ).also { it.initialize() }
}

/**
 * Creates one Agent child for the exact runtime handle already resolved by its
 * owning persisted Session.
 */
public fun interface PersistedSessionAgentViewModelFactory {
    public suspend fun create(
        session: KodexAgentSession,
        address: AgentAddress,
        parentAddress: AgentAddress?,
        ownerScope: CoroutineScope,
        isRoot: Boolean,
    ): AgentViewModel
}

private class PersistedSessionViewModelImpl(
    override val sessionIndex: Int,
    private val rootSession: KodexAgentSession,
    private val repository: KodexSessionRepository,
    private val ownerScope: CoroutineScope,
    private val agentFactory: PersistedSessionAgentViewModelFactory,
) : PersistedSessionViewModel {
    private val mutex = Mutex()
    private val agents = linkedMapOf<AgentAddress, AgentViewModel>()
    private val sessions = linkedMapOf<AgentAddress, KodexAgentSession>()
    private val parentAddresses = linkedMapOf<AgentAddress, AgentAddress?>()
    private val depths = linkedMapOf<AgentAddress, Int>()
    private val threadNames = linkedMapOf<AgentAddress, String?>()
    private val topologyObservers = linkedMapOf<AgentAddress, Job>()
    private val mutableName = MutableStateFlow("Session $sessionIndex")
    private val mutableSummary = MutableStateFlow(PersistedSessionSummaryState())
    private lateinit var mutableSelectedAgent: MutableStateFlow<AgentViewModel>
    private lateinit var mutableTopology: MutableStateFlow<PersistedSessionTopologyState>
    private val mutableLifecycle =
        MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Open)
    private val mutableNotification = MutableStateFlow<PersistedSessionNotification?>(null)
    private var summaryRevision = 0L
    private var topologyRevision = 0L
    private var nextNotificationId = 1L
    private var closed = false

    override lateinit var rootAgent: AgentViewModel
        private set
    override val name: StateFlow<String> = mutableName.asStateFlow()
    override val settings: StateFlow<KodexAgentSettings>
        get() = rootAgent.settings
    override val models: StateFlow<List<ModelInfo>>
        get() = rootAgent.models
    override val selectedAgent: StateFlow<AgentViewModel>
        get() = mutableSelectedAgent.asStateFlow()
    override val summary: StateFlow<PersistedSessionSummaryState> = mutableSummary.asStateFlow()
    override val topology: StateFlow<PersistedSessionTopologyState>
        get() = mutableTopology.asStateFlow()
    override val lifecycle: StateFlow<PersistedSessionLifecycleState> =
        mutableLifecycle.asStateFlow()
    override val notification: StateFlow<PersistedSessionNotification?> =
        mutableNotification.asStateFlow()

    suspend fun initialize() {
        val rootAddress = AgentAddress(sessionIndex, rootSession.storage.id)
        sessions[rootAddress] = rootSession
        parentAddresses[rootAddress] = null
        depths[rootAddress] = 0
        val root = materialize(rootAddress)
        rootAgent = root
        threadNames[rootAddress] = root.settings.value.threadName.takeIf(String::isNotBlank)
        mutableSelectedAgent = MutableStateFlow(root)
        mutableTopology = MutableStateFlow(
            PersistedSessionTopologyState(
                rootAddress = rootAddress,
                nodes = listOf(
                    projectNode(rootAddress, PersistedAgentMaterializationState.Loaded),
                ),
            ),
        )
        observe(rootAddress)
        refresh()
    }

    override suspend fun refresh() = mutex.withLock {
        ensureOpen()
        discoverDirectChildren(rootAgent.address, materialize = false)
        refreshProjection()
    }

    override suspend fun selectAgent(address: AgentAddress): AgentViewModel = mutex.withLock {
        require(address.sessionIndex == sessionIndex) {
            "Agent belongs to another persisted Session."
        }
        ensureOpen()
        val selected = agents[address] ?: materialize(findSession(address))
        mutableSelectedAgent.value = selected
        refreshProjection()
        selected
    }

    override suspend fun materializeDirectChildren(parentAddress: AgentAddress) =
        mutex.withLock {
            ensureOpen()
            discoverDirectChildren(parentAddress, materialize = true)
            agents[parentAddress]?.loadDirectChildren()
            refreshProjection()
        }

    override suspend fun fork(
        source: AgentViewModel,
        target: AgentHistoryTarget,
    ): Int = mutex.withLock {
        ensureOpen()
        val owned = agents[source.address]
        require(owned === source) {
            "Fork source is not owned by this persisted Session."
        }
        require(target.generation == source.history.window.value.generation) {
            "Fork target generation is stale."
        }
        require(
            !source.execution.value.running &&
                source.execution.value.capabilities.canForkHistory,
        ) {
            "Cannot fork a running or unavailable Agent."
        }
        val sourceSession = sessions.getValue(source.address)
        val sourceIndex = target.untilExclusive - 1
        require(sourceSession.runtime.storage.latestIndex() >= sourceIndex) {
            "Fork boundary is outside the source storage."
        }
        require(
            sourceSession.runtime.storage.stable.floorToIndex(sourceIndex) == sourceIndex,
        ) {
            "Fork history entry $sourceIndex is no longer committed."
        }
        val targetIndex = repository.create()
        try {
            val targetSession = repository.open(targetIndex)
            targetSession.runtime.modify { storage ->
                sourceSession.runtime.storage.forkTo(target.untilExclusive, storage)
                val latest = storage.latestIndex()
                val boundary = storage.settings[sourceIndex]
                val baseTitle = boundary.threadName.trim().ifEmpty {
                    "Session $targetIndex"
                }
                storage.settings[latest + 1] = boundary.copy(
                    threadName = "[fork] $baseTitle",
                )
            }
            targetIndex
        } catch (failure: Throwable) {
            repository.delete(targetIndex)
            publishFailure("Unable to fork Session.", failure)
            throw failure
        }
    }

    override suspend fun rename(name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "A Session name cannot be blank." }
        rootAgent.renameThread(normalized)
        mutex.withLock {
            ensureOpen()
            threadNames[rootAgent.address] = normalized
            refreshProjection()
        }
    }

    override suspend fun updateModel(model: OpenAiModelId) = rootAgent.updateModel(model)

    override suspend fun updateWorkingDirectory(workingDirectory: Path) =
        rootAgent.updateWorkingDirectory(workingDirectory)

    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort) =
        rootAgent.updateReasoningEffort(reasoningEffort)

    override suspend fun updateServiceTier(serviceTier: ServiceTier) =
        rootAgent.updateServiceTier(serviceTier)

    override suspend fun updateMode(mode: ModeKind) = rootAgent.updateMode(mode)

    override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ) = rootAgent.updateModelConfiguration(model, reasoningEffort, serviceTier)

    override fun dismissNotification(notificationId: Long) {
        val current = mutableNotification.value ?: return
        if (current.id == notificationId) {
            mutableNotification.compareAndSet(current, null)
        }
    }

    override suspend fun shutdown() = mutex.withLock {
        closeOwnedResources()
    }

    override fun close() {
        closeOwnedResources()
    }

    private fun closeOwnedResources() {
        if (closed) return
        closed = true
        mutableLifecycle.value = PersistedSessionLifecycleState.Closing
        topologyObservers.values.forEach(Job::cancel)
        topologyObservers.clear()
        agents.values.toList().asReversed().forEach(AgentViewModel::close)
        agents.clear()
        sessions.clear()
        mutableLifecycle.value = PersistedSessionLifecycleState.Closed
        ownerScope.cancel()
    }

    private suspend fun discoverDirectChildren(
        parentAddress: AgentAddress,
        materialize: Boolean,
    ) {
        val parent = findKnownSession(parentAddress)
        val parentDepth = requireNotNull(depths[parentAddress])
        parent.subagents.listEntries().forEach { entry ->
            val child = parent.subagents.open(entry.entryIndex)
            if (child.runtime.latestIndex.value < 0) return@forEach
            val address = AgentAddress(sessionIndex, child.storage.id)
            sessions[address] = child
            parentAddresses[address] = parentAddress
            depths[address] = parentDepth + 1
            threadNames[address] = entry.threadName
            observe(address)
            if (materialize) materialize(address)
        }
    }

    private suspend fun findSession(address: AgentAddress): AgentAddress {
        if (address in sessions) return address
        suspend fun visit(parentAddress: AgentAddress): Boolean {
            discoverDirectChildren(parentAddress, materialize = false)
            if (address in sessions) return true
            val children = parentAddresses
                .filterValues { parent -> parent == parentAddress }
                .keys
                .toList()
            return children.any { childAddress -> visit(childAddress) }
        }
        require(visit(rootAgent.address)) {
            "Agent ${address.agentId} does not belong to Session $sessionIndex."
        }
        return address
    }

    private fun findKnownSession(address: AgentAddress): KodexAgentSession =
        requireNotNull(sessions[address]) {
            "Agent ${address.agentId} has not been discovered."
        }

    private suspend fun materialize(address: AgentAddress): AgentViewModel {
        agents[address]?.let { return it }
        val created = agentFactory.create(
            session = findKnownSession(address),
            address = address,
            parentAddress = parentAddresses[address],
            ownerScope = ownerScope,
            isRoot = parentAddresses[address] == null,
        )
        agents[address] = created
        threadNames[address] = created.settings.value.threadName.takeIf(String::isNotBlank)
        return created
    }

    private fun observe(address: AgentAddress) {
        if (address in topologyObservers) return
        val session = findKnownSession(address)
        topologyObservers[address] = ownerScope.launch {
            launch {
                var observedEntries = session.subagents.entries.value
                session.subagents.entries.collect { currentEntries ->
                    if (currentEntries != observedEntries) {
                        observedEntries = currentEntries
                        mutex.withLock {
                            if (!closed) {
                                discoverDirectChildren(address, materialize = false)
                                refreshProjection()
                            }
                        }
                    }
                }
            }
            launch {
                combine(
                    session.runtime.state
                        .map(KodexAgentStateValue::toExecutionPhase)
                        .distinctUntilChanged(),
                    session.runtime.runningTurn
                        .map { runningTurn -> runningTurn != null }
                        .distinctUntilChanged(),
                    session.runtime.latestIndex,
                ) { phase, running, latestIndex ->
                    LightweightAgentRuntimeState(phase, running, latestIndex)
                }.distinctUntilChanged().collect { runtimeState ->
                    if (runtimeState.latestIndex < 0) return@collect
                    val threadName = session.storage.settings[runtimeState.latestIndex]
                        .threadName
                        .takeIf(String::isNotBlank)
                    mutex.withLock {
                        if (!closed) {
                            threadNames[address] = threadName
                            refreshProjection()
                        }
                    }
                }
            }
        }
    }

    private fun refreshProjection() {
        mutableName.value = threadNames[rootAgent.address] ?: "Session $sessionIndex"

        val projectedSummary = PersistedSessionSummaryState(
            rootRunning = rootSession.runtime.runningTurn.value != null,
            aggregateRunning = sessions.values.any {
                it.runtime.runningTurn.value != null
            },
            lastActivityAt = null,
            agentCount = sessions.size.coerceAtLeast(1),
        )
        if (mutableSummary.value.copy(revision = 0) != projectedSummary) {
            check(summaryRevision < Long.MAX_VALUE) {
                "Session summary revisions are exhausted."
            }
            summaryRevision += 1
            mutableSummary.value = projectedSummary.copy(revision = summaryRevision)
        }

        val projectedTopology = PersistedSessionTopologyState(
            rootAddress = rootAgent.address,
            nodes = sessions.keys
                .sortedWith(
                    compareBy<AgentAddress> { depths.getValue(it) }
                        .thenBy(AgentAddress::agentId),
                )
                .map { address ->
                    projectNode(
                        address = address,
                        materialization = if (address in agents) {
                            PersistedAgentMaterializationState.Loaded
                        } else {
                            PersistedAgentMaterializationState.Unloaded
                        },
                    )
                },
        )
        if (mutableTopology.value.copy(revision = 0) != projectedTopology) {
            check(topologyRevision < Long.MAX_VALUE) {
                "Session topology revisions are exhausted."
            }
            topologyRevision += 1
            mutableTopology.value = projectedTopology.copy(revision = topologyRevision)
        }
    }

    private fun projectNode(
        address: AgentAddress,
        materialization: PersistedAgentMaterializationState,
    ): PersistedSessionTopologyNode {
        val session = findKnownSession(address)
        return PersistedSessionTopologyNode(
            address = address,
            parentAddress = parentAddresses[address],
            depth = depths.getValue(address),
            threadName = threadNames[address],
            phase = session.runtime.state.value.toExecutionPhase(),
            running = session.runtime.runningTurn.value != null,
            activityVersion = session.runtime.latestIndex.value.coerceAtLeast(0).toLong(),
            hasChildren = session.subagents.entries.value.isNotEmpty(),
            materialization = materialization,
        )
    }

    private fun publishFailure(message: String, failure: Throwable) {
        val id = nextNotificationId
        check(id < Long.MAX_VALUE) { "Session notification ids are exhausted." }
        nextNotificationId += 1
        mutableNotification.value = PersistedSessionNotification(
            id = id,
            level = PersistedSessionNotificationLevel.Error,
            message = message,
            detail = failure.stackTraceToString(),
        )
    }

    private fun ensureOpen() {
        check(!closed) { "Persisted Session ViewModel is closed." }
    }
}

private class SessionCatalogViewModelImpl(
    private val repository: KodexSessionRepository,
    private val scope: CoroutineScope,
) : SessionCatalogViewModel {
    private val mutableSessions = MutableStateFlow<List<SessionCatalogEntry>>(emptyList())
    override val sessions: StateFlow<List<SessionCatalogEntry>> = mutableSessions.asStateFlow()

    override suspend fun refresh() {
        mutableSessions.value = repository.listEntries()
            .sortedWith(
                compareByDescending<KodexSessionEntry> { entry -> entry.lastActivityAt }
                    .thenByDescending(KodexSessionEntry::entryIndex),
            )
            .map { entry ->
                SessionCatalogEntry(
                    sessionIndex = entry.entryIndex,
                    threadName = entry.threadName,
                    lastActivityAt = entry.lastActivityAt,
                )
            }
    }

    override fun close() {
        scope.cancel()
    }
}

@Factory(binds = [SessionCatalogViewModelFactory::class])
internal fun createSessionCatalogViewModelFactory(
    @InjectedParam repository: KodexSessionRepository,
    @InjectedParam scope: CoroutineScope,
): SessionCatalogViewModelFactory =
    SessionCatalogViewModelFactory {
        SessionCatalogViewModelImpl(repository, scope.supervisorChildScope())
    }

private data class LightweightAgentRuntimeState(
    val phase: AgentExecutionPhase,
    val running: Boolean,
    val latestIndex: Int,
)

private fun KodexAgentStateValue.toExecutionPhase(): AgentExecutionPhase = when (this) {
    KodexAgentStateValue.Empty -> AgentExecutionPhase.Empty
    KodexAgentStateValue.UserMessage -> AgentExecutionPhase.UserMessage
    is KodexAgentStateValue.RequestResponse -> AgentExecutionPhase.Responding
    KodexAgentStateValue.AssistantMessage -> AgentExecutionPhase.AssistantMessage
    is KodexAgentStateValue.ToolPending -> AgentExecutionPhase.ToolPending
    KodexAgentStateValue.ToolCompleted -> AgentExecutionPhase.ToolCompleted
    KodexAgentStateValue.ExternalWrite -> AgentExecutionPhase.ExternalWrite
    KodexAgentStateValue.Compacting -> AgentExecutionPhase.Compacting
}
