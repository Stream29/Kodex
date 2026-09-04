package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionNotification
import io.github.stream29.kodex.app.session.contract.PersistedSessionNotificationLevel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Creates the root Agent for its owning persisted Session. */
public fun interface PersistedSessionAgentViewModelFactory {
    public suspend fun create(
        session: KodexAgentSession,
        ownerScope: CoroutineScope,
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
    private val mutableName = MutableStateFlow("Session $sessionIndex")
    private val mutableLifecycle =
        MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Open)
    private val mutableNotification = MutableStateFlow<PersistedSessionNotification?>(null)
    private var nextNotificationId = 1L
    private var closed = false

    override lateinit var rootAgent: AgentViewModel
        private set
    override val name: StateFlow<String> = mutableName.asStateFlow()
    override val settings: StateFlow<KodexAgentSettings>
        get() = rootAgent.settings
    override val models: StateFlow<List<ModelInfo>>
        get() = rootAgent.models
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
        rootAgent = agentFactory.create(
            session = rootSession,
            ownerScope = ownerScope,
        )
        ownerScope.launch {
            rootAgent.settings.collect {
                refresh()
            }
        }
        refresh()
    }

    override suspend fun refresh() = mutex.withLock {
        ensureOpen()
        refreshName()
    }

    override suspend fun fork(
        source: AgentViewModel,
        target: AgentHistoryTarget,
    ): Int = mutex.withLock {
        ensureOpen()
        require(source === rootAgent) {
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
        val sourceIndex = target.untilExclusive - 1
        val sourceStorage = rootSession.runtime.storage
        require(sourceStorage.latestIndex() >= sourceIndex) {
            "Fork boundary is outside the source storage."
        }
        require(
            sourceStorage.index.getExact(sourceIndex) != null ||
                sourceStorage.work.getExact(sourceIndex) != null,
        ) {
            "Fork history entry $sourceIndex is no longer committed."
        }
        val targetIndex = repository.createFork(
            sourceEntryIndex = sessionIndex,
        )
        try {
            val targetSession = repository.open(targetIndex)
            try {
                targetSession.runtime.modify { storage ->
                    val boundary = storage.settings[sourceIndex]
                    storage.revert(target.untilExclusive)
                    val latest = storage.latestIndex()
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
            sourceEntryIndex = sessionIndex,
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
            refreshName()
        }
    }

    override suspend fun updateModel(model: OpenAiModelId) = rootAgent.updateModel(model)

    override suspend fun updateWorkingDirectory(workingDirectory: Path) =
        rootAgent.updateWorkingDirectory(workingDirectory)

    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort) =
        rootAgent.updateReasoningEffort(reasoningEffort)

    override suspend fun updateServiceTier(serviceTier: ServiceTier) =
        rootAgent.updateServiceTier(serviceTier)

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
        rootAgent.close()
        mutableLifecycle.value = PersistedSessionLifecycleState.Closed
        ownerScope.cancel()
    }

    private fun refreshName() {
        val threadName = rootAgent.settings.value.threadName
            .takeIf(String::isNotBlank)
        mutableName.value = threadName ?: "Session $sessionIndex"
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
            val sessions = entries.map { entry ->
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
