package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
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
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

/** Creates one repository as a child of [ownerScope]. */
public fun interface KodexSessionRepositoryFactory {
    public suspend fun create(ownerScope: CoroutineScope): KodexRootSessionRepository
}

/** Repository operations shared by persisted Session, catalog, and draft factories. */
@Factory(binds = [PersistedSessionViewModelRegistry::class])
public class DefaultPersistedSessionViewModelRegistry(
    @InjectedParam private val repositoryFactory: KodexSessionRepositoryFactory,
    @InjectedParam private val scope: CoroutineScope,
    @InjectedParam private val agentFactory: PersistedSessionAgentViewModelFactory,
) : PersistedSessionViewModelRegistry {
    private val mutex = Mutex()
    private val opened = linkedMapOf<Int, PersistedSessionViewModelImpl>()

    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel = mutex.withLock {
        opened[sessionIndex]?.let { existing ->
            existing.unarchive()
            return@withLock existing
        }
        createOpened(sessionIndex).also { created ->
            opened[sessionIndex] = created
        }
    }

    override suspend fun create(
        initialSettings: (sessionIndex: Int) -> KodexAgentSettings,
    ): PersistedSessionViewModel = mutex.withLock {
        val ownerScope = scope.supervisorChildScope()
        var repository: KodexRootSessionRepository? = null
        var index: Int? = null
        try {
            val createdRepository = repositoryFactory.create(ownerScope)
            repository = createdRepository
            val createdIndex = createdRepository.create()
            index = createdIndex
            buildOpened(createdIndex, createdRepository, ownerScope) { rootSession ->
                rootSession.runtime.modify { storage ->
                    storage.initialize(initialSettings(createdIndex))
                }
            }.also { created ->
                opened[createdIndex] = created
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                repository?.let { createdRepository ->
                    index?.let { createdIndex ->
                        runCatching { createdRepository.delete(createdIndex) }
                            .onFailure(failure::addSuppressed)
                    }
                }
                ownerScope.cancelAndJoin()
            }
            throw failure
        }
    }

    override suspend fun archive(sessionIndex: Int): Unit = mutex.withLock {
        opened[sessionIndex]?.let { existing ->
            existing.archive()
            return@withLock
        }
        withRepository { repository ->
            repository.getEntry(sessionIndex).archive()
        }
    }

    override suspend fun unarchive(sessionIndex: Int): Unit = mutex.withLock {
        opened[sessionIndex]?.let { existing ->
            existing.unarchive()
            return@withLock
        }
        withRepository { repository ->
            repository.getEntry(sessionIndex).unarchive()
        }
    }

    override suspend fun fork(sessionIndex: Int): Int = mutex.withLock {
        opened[sessionIndex]?.let { existing -> return@withLock existing.fork() }
        val ownerScope = scope.supervisorChildScope()
        var temporary: PersistedSessionViewModelImpl? = null
        try {
            val repository = repositoryFactory.create(ownerScope)
            require(sessionIndex in repository.list()) {
                "No persisted Session at index $sessionIndex."
            }
            temporary = buildOpened(sessionIndex, repository, ownerScope)
            temporary.fork()
        } finally {
            withContext(NonCancellable) {
                temporary?.shutdown() ?: ownerScope.cancelAndJoin()
            }
        }
    }

    override suspend fun delete(sessionIndex: Int): Boolean = mutex.withLock {
        withRepository { repository ->
            if (sessionIndex !in repository.list()) return@withRepository false
            opened.remove(sessionIndex)?.shutdown()
            repository.delete(sessionIndex)
            true
        }
    }

    /**
     * Removes a Session created by a failed draft materialization.
     *
     * This is intentionally an implementation-layer rollback boundary rather
     * than part of the frontend Session contract.
     */
    override suspend fun rollbackCreated(sessionIndex: Int) {
        mutex.withLock {
            withRepository { repository ->
                opened.remove(sessionIndex)?.shutdown()
                if (sessionIndex in repository.list()) repository.delete(sessionIndex)
            }
        }
    }

    override suspend fun release(sessionIndex: Int): Unit = mutex.withLock {
        opened.remove(sessionIndex)?.shutdown()
    }

    override suspend fun shutdown(): Unit = mutex.withLock {
        opened.values.toList().forEach { it.shutdown() }
        opened.clear()
    }

    private suspend fun createOpened(
        index: Int,
    ): PersistedSessionViewModelImpl {
        val ownerScope = scope.supervisorChildScope()
        return try {
            val repository = repositoryFactory.create(ownerScope)
            require(index in repository.list()) {
                "No persisted Session at index $index."
            }
            buildOpened(index, repository, ownerScope).also { created ->
                created.unarchive()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { ownerScope.cancelAndJoin() }
            throw failure
        }
    }

    private suspend fun buildOpened(
        index: Int,
        repository: KodexRootSessionRepository,
        ownerScope: CoroutineScope,
        initialize: suspend (KodexAgentSession) -> Unit = {},
    ): PersistedSessionViewModelImpl {
        val rootSession = repository.open(index)
        initialize(rootSession)
        return PersistedSessionViewModelImpl(
            sessionIndex = index,
            rootSession = rootSession,
            repository = repository,
            ownerScope = ownerScope,
            agentFactory = agentFactory,
        ).also { it.initialize() }
    }

    private suspend fun <T> withRepository(
        block: suspend (KodexRootSessionRepository) -> T,
    ): T {
        val ownerScope = scope.supervisorChildScope()
        return try {
            block(repositoryFactory.create(ownerScope))
        } finally {
            withContext(NonCancellable) { ownerScope.cancelAndJoin() }
        }
    }
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
    private val repository: KodexRootSessionRepository,
    private val ownerScope: CoroutineScope,
    private val agentFactory: PersistedSessionAgentViewModelFactory,
) : PersistedSessionViewModel {
    private val mutex = Mutex()
    private val agents = linkedMapOf<AgentAddress, AgentViewModel>()
    private val sessions = linkedMapOf<AgentAddress, KodexAgentSession>()
    private val parentAddresses = linkedMapOf<AgentAddress, AgentAddress?>()
    private val depths = linkedMapOf<AgentAddress, Int>()
    private val directChildEdges = linkedMapOf<AgentAddress, List<TopologyChildEdge>>()
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

    suspend fun archive() {
        ensureOpen()
        repository.getEntry(sessionIndex).archive()
    }

    suspend fun unarchive() {
        ensureOpen()
        repository.getEntry(sessionIndex).unarchive()
    }

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
        require(source.history.contains(target.generation, target.storageIndex)) {
            "Fork target is stale."
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
        val targetIndex = repository.createFork(
            source = sourceSession.runtime.storage,
            from = 0,
            until = target.untilExclusive,
        )
        try {
            val targetSession = repository.open(targetIndex)
            try {
                targetSession.runtime.modify { storage ->
                    val latest = storage.latestIndex()
                    val boundary = storage.settings[sourceIndex]
                    val baseTitle = boundary.threadName.trim().ifEmpty {
                        "Session $targetIndex"
                    }
                    storage.settings[latest + 1] = boundary.copy(
                        threadName = "[fork] $baseTitle",
                    )
                }
            } finally {
                withContext(NonCancellable) { targetSession.cancelAndJoin() }
            }
            targetIndex
        } catch (failure: Throwable) {
            repository.delete(targetIndex)
            publishFailure("Unable to fork Session.", failure)
            throw failure
        }
    }

    override suspend fun fork(): Int = mutex.withLock {
        ensureOpen()
        require(
            !rootAgent.execution.value.running &&
                rootAgent.execution.value.capabilities.canForkHistory,
        ) {
            "Cannot fork a running or unavailable Session."
        }
        val sourceStorage = rootSession.runtime.storage
        val sourceIndex = sourceStorage.latestIndex()
        require(sourceIndex >= 0) { "Cannot fork an uninitialized Session." }
        val targetIndex = repository.createFork(
            source = sourceStorage,
            from = 0,
            until = sourceIndex + 1,
        )
        try {
            val targetSession = repository.open(targetIndex)
            try {
                targetSession.runtime.modify { storage ->
                    val latest = storage.latestIndex()
                    val boundary = storage.settings[sourceIndex]
                    val baseTitle = boundary.threadName.trim().ifEmpty {
                        "Session $targetIndex"
                    }
                    storage.settings[latest + 1] = boundary.copy(
                        threadName = "[fork] $baseTitle",
                    )
                }
            } finally {
                withContext(NonCancellable) { targetSession.cancelAndJoin() }
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

    override suspend fun updateAgentMode(agentMode: AgentMode) =
        rootAgent.updateAgentMode(agentMode)

    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) =
        rootAgent.updateRequestUserInputMode(mode)

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
        withContext(NonCancellable) { ownerScope.cancelAndJoin() }
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
        directChildEdges.clear()
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
        val children = mutableListOf<DiscoveredTopologyChild>()
        parent.subagents.listEntries().forEach { entry ->
            val child = parent.subagents.open(entry.entryIndex)
            if (child.runtime.latestIndex.value < 0) return@forEach
            val address = AgentAddress(sessionIndex, child.storage.id)
            children += DiscoveredTopologyChild(
                edge = TopologyChildEdge(entry.entryIndex, address),
                session = child,
                threadName = entry.threadName,
            )
        }
        require(children.map { child -> child.edge.entryIndex }.distinct().size == children.size) {
            "Direct Agent entry indices must be unique."
        }
        require(children.map { child -> child.edge.address }.distinct().size == children.size) {
            "Direct Agent addresses must be unique."
        }
        children.forEach { child ->
            if (child.edge.address in parentAddresses) {
                require(parentAddresses[child.edge.address] == parentAddress) {
                    "A discovered Agent address cannot move between topology parents."
                }
            }
        }

        val newEdges = children.map(DiscoveredTopologyChild::edge)
        val newAddresses = newEdges.mapTo(mutableSetOf(), TopologyChildEdge::address)
        val removedRoots = directChildEdges[parentAddress]
            .orEmpty()
            .map(TopologyChildEdge::address)
            .filterNot(newAddresses::contains)
        val removedAddresses = linkedSetOf<AgentAddress>()
        removedRoots.forEach { removedRoot ->
            discoveredSubtreeAddresses(removedRoot).forEach { removedAddress ->
                check(removedAddresses.add(removedAddress)) {
                    "Removed Agent topology subtrees must not overlap."
                }
            }
        }

        directChildEdges[parentAddress] = newEdges
        children.forEach { child ->
            val address = child.edge.address
            sessions[address] = child.session
            parentAddresses[address] = parentAddress
            depths[address] = parentDepth + 1
            threadNames[address] = child.threadName
        }

        if (mutableSelectedAgent.value.address in removedAddresses) {
            var fallbackAddress = parentAddresses[mutableSelectedAgent.value.address]
            while (fallbackAddress != null && fallbackAddress in removedAddresses) {
                fallbackAddress = requireNotNull(parentAddresses[fallbackAddress])
            }
            val retainedAddress = requireNotNull(fallbackAddress) {
                "Removing a topology subtree must retain the root Agent."
            }
            mutableSelectedAgent.value = agents[retainedAddress] ?: materialize(retainedAddress)
        }
        removedAddresses.toList().asReversed().forEach(::removeDiscoveredAgent)

        children.forEach { child -> observe(child.edge.address) }
        if (materialize) {
            children.forEach { child -> materialize(child.edge.address) }
        }
    }

    private suspend fun findSession(address: AgentAddress): AgentAddress {
        if (address in sessions) return address
        suspend fun visit(parentAddress: AgentAddress): Boolean {
            discoverDirectChildren(parentAddress, materialize = false)
            if (address in sessions) return true
            return directChildEdges[parentAddress].orEmpty().any { child ->
                visit(child.address)
            }
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
            nodes = topologyAddressesInPreorder()
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

    private fun topologyAddressesInPreorder(): List<AgentAddress> {
        val addresses = mutableListOf<AgentAddress>()
        val visited = mutableSetOf<AgentAddress>()

        fun visit(address: AgentAddress) {
            check(visited.add(address)) {
                "A discovered Agent may occur only once in the topology."
            }
            check(address in sessions) {
                "Every topology edge must resolve to a discovered Agent."
            }
            addresses += address
            directChildEdges[address].orEmpty().forEach { child ->
                check(parentAddresses[child.address] == address) {
                    "Every topology edge must agree with its child's parent."
                }
                visit(child.address)
            }
        }

        visit(rootAgent.address)
        check(visited.size == sessions.size) {
            "Every discovered Agent must be reachable from the topology root."
        }
        return addresses
    }

    private fun discoveredSubtreeAddresses(rootAddress: AgentAddress): List<AgentAddress> {
        val addresses = mutableListOf<AgentAddress>()
        val visited = mutableSetOf<AgentAddress>()

        fun visit(address: AgentAddress) {
            check(visited.add(address)) {
                "A discovered Agent may occur only once in a topology subtree."
            }
            addresses += address
            directChildEdges[address].orEmpty().forEach { child -> visit(child.address) }
        }

        visit(rootAddress)
        return addresses
    }

    private fun removeDiscoveredAgent(address: AgentAddress) {
        check(address != rootAgent.address) {
            "The persisted Session root Agent cannot be removed from its topology."
        }
        directChildEdges.remove(address)
        topologyObservers.remove(address)?.cancel()
        agents.remove(address)?.close()
        sessions.remove(address)
        parentAddresses.remove(address)
        depths.remove(address)
        threadNames.remove(address)
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
    private val repositoryFactory: KodexSessionRepositoryFactory,
    private val scope: CoroutineScope,
    private val forkSession: suspend (sessionIndex: Int) -> Int,
    private val deleteSession: suspend (sessionIndex: Int) -> Boolean,
) : SessionCatalogViewModel {
    private val commandMutex = Mutex()
    private val mutableState = MutableStateFlow<SessionCatalogState>(SessionCatalogState.Unloaded)
    private var sessionRepository: KodexRootSessionRepository? = null
    private var rootEntries: Map<Int, KodexRootSessionEntry> = emptyMap()
    override val state: StateFlow<SessionCatalogState> = mutableState.asStateFlow()

    override suspend fun refresh(): Unit = commandMutex.withLock {
        reload(showArchived = mutableState.value.showArchived)
    }

    override suspend fun setShowArchived(showArchived: Boolean): Unit = commandMutex.withLock {
        if (mutableState.value.showArchived == showArchived) return@withLock
        reload(showArchived)
    }

    override suspend fun archive(sessionIndex: Int): Unit = commandMutex.withLock {
        val previous = mutableState.value
        getRootEntry(sessionIndex).archive()
        updateEntry(previous, sessionIndex, archived = true)
    }

    override suspend fun unarchive(sessionIndex: Int): Unit = commandMutex.withLock {
        val previous = mutableState.value
        getRootEntry(sessionIndex).unarchive()
        updateEntry(previous, sessionIndex, archived = false)
    }

    override suspend fun fork(sessionIndex: Int): Int = commandMutex.withLock {
        val targetIndex = forkSession(sessionIndex)
        reloadRepository(mutableState.value.showArchived)
        targetIndex
    }

    override suspend fun delete(sessionIndex: Int): Boolean = commandMutex.withLock {
        if (!deleteSession(sessionIndex)) return@withLock false
        val previous = mutableState.value
        sessionRepository?.let { repository ->
            withContext(NonCancellable) { repository.cancelAndJoin() }
        }
        sessionRepository = null
        rootEntries = emptyMap()
        when (previous) {
            SessionCatalogState.Unloaded -> Unit
            is SessionCatalogState.Loading -> Unit
            is SessionCatalogState.Loaded -> {
                mutableState.value = previous.copy(
                    sessions = previous.sessions.filterNot { entry ->
                        entry.sessionIndex == sessionIndex
                    },
                )
            }
        }
        true
    }

    private suspend fun reloadRepository(showArchived: Boolean) {
        sessionRepository?.let { repository ->
            withContext(NonCancellable) { repository.cancelAndJoin() }
        }
        sessionRepository = null
        rootEntries = emptyMap()
        reload(showArchived)
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun reload(showArchived: Boolean) {
        val previous = mutableState.value
        mutableState.value = SessionCatalogState.Loading(showArchived)
        try {
            val entries = getOrCreateRepository().listEntries(includeArchived = showArchived)
                .sortedWith(
                    compareByDescending<KodexRootSessionEntry> { entry -> entry.lastActivityAt }
                        .thenByDescending { entry -> entry.entryIndex },
                )
            val sessions = entries
                .map { entry ->
                    SessionCatalogEntry(
                        sessionIndex = entry.entryIndex,
                        threadName = entry.threadName,
                        lastActivityAt = entry.lastActivityAt,
                        archived = entry.archived,
                    )
                }
            rootEntries = entries.associateBy { entry -> entry.entryIndex }
            mutableState.value = SessionCatalogState.Loaded(showArchived, sessions)
        } catch (failure: Throwable) {
            mutableState.value = previous
            throw failure
        }
    }

    private suspend fun updateEntry(
        previous: SessionCatalogState,
        sessionIndex: Int,
        archived: Boolean,
    ) {
        when (previous) {
            SessionCatalogState.Unloaded -> Unit
            is SessionCatalogState.Loading -> Unit
            is SessionCatalogState.Loaded -> {
                val index = previous.sessions.indexOfFirst { entry ->
                    entry.sessionIndex == sessionIndex
                }
                if (index < 0) {
                    reload(previous.showArchived)
                    return
                }
                val updated = previous.sessions[index].copy(archived = archived)
                val sessions = if (archived && !previous.showArchived) {
                    rootEntries = rootEntries - sessionIndex
                    previous.sessions.toMutableList().apply { removeAt(index) }
                } else {
                    previous.sessions.toMutableList().apply { set(index, updated) }
                }
                mutableState.value = previous.copy(sessions = sessions)
            }
        }
    }

    private suspend fun getOrCreateRepository(): KodexRootSessionRepository =
        sessionRepository ?: repositoryFactory.create(scope).also { sessionRepository = it }

    private suspend fun getRootEntry(sessionIndex: Int): KodexRootSessionEntry {
        rootEntries[sessionIndex]?.let { return it }
        return requireNotNull(
            getOrCreateRepository().listEntries()
                .firstOrNull { entry -> entry.entryIndex == sessionIndex },
        ) {
            "No root Session entry exists at index $sessionIndex."
        }
    }
}

@Factory(binds = [SessionCatalogViewModelFactory::class])
internal fun createSessionCatalogViewModelFactory(
    @InjectedParam repositoryFactory: KodexSessionRepositoryFactory,
    @InjectedParam scope: CoroutineScope,
): SessionCatalogViewModelFactory =
    SessionCatalogViewModelFactory { forkSession, deleteSession ->
        SessionCatalogViewModelImpl(
            repositoryFactory = repositoryFactory,
            scope = scope.supervisorChildScope(),
            forkSession = forkSession,
            deleteSession = deleteSession,
        )
    }

private data class LightweightAgentRuntimeState(
    val phase: AgentExecutionPhase,
    val running: Boolean,
    val latestIndex: Int,
)

private data class TopologyChildEdge(
    val entryIndex: Int,
    val address: AgentAddress,
)

private data class DiscoveredTopologyChild(
    val edge: TopologyChildEdge,
    val session: KodexAgentSession,
    val threadName: String?,
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
