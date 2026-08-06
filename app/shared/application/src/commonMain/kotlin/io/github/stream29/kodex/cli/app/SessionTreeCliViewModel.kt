package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.newsession.NewSessionViewModel
import io.github.stream29.kodex.cli.newsession.NewSessionViewModel as createNewSessionViewModel
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.cli.session.AgentRuntimeTreeEntry
import io.github.stream29.kodex.cli.session.RootSessionViewModel
import io.github.stream29.kodex.cli.session.RootSessionViewState
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel
import io.github.stream29.kodex.cli.session.SessionRepositoryViewState
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/** Application-level composition of root session trees. It owns no Agent execution. */
public data class SessionTreeCliState(
    public val globalSettings: KodexGlobalSettings,
    public val sessions: SessionRepositoryViewState = SessionRepositoryViewState(),
    public val tabs: List<SessionTabViewState> = emptyList(),
    public val activeTab: SessionTabTarget,
    public val selectedTree: RootSessionViewState? = null,
    public val models: List<ModelInfo> = emptyList(),
    public val exitRequested: Boolean = false,
)

public class SessionTreeCliViewModel internal constructor(
    private val repository: SessionRepositoryViewModel,
    private val globalSettings: KodexGlobalSettingsStore,
    private val workingDirectory: Path,
    modelCatalog: OpenAiModelCatalog,
    public val authStore: KodexAuthStore,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val scope = parentScope.supervisorChildScope()
    private val lifecycleMutex = Mutex()
    private var selectedTreeCollection: Job? = null
    private val newSessionTabs = linkedMapOf<Long, NewSessionViewModel>()
    private var nextNewSessionTabId = 1L
    private var nextNewSessionOrdinal = 1
    private val firstNewSessionTab = allocateNewSessionTab()
    private var tabRegistry = SessionTabRegistryState(
        tabs = listOf(firstNewSessionTab),
        activeTarget = firstNewSessionTab,
    )

    private val mutableState = MutableStateFlow(
        SessionTreeCliState(
            globalSettings = globalSettings.settings.value,
            tabs = tabViewStates(repository.state.value),
            activeTab = tabRegistry.activeTarget,
        ),
    )
    public val state: StateFlow<SessionTreeCliState> = mutableState.asStateFlow()

    init {
        scope.launch {
            repository.state.collect { sessions ->
                lifecycleMutex.withLock { reconcileRepositoryState(sessions) }
            }
        }
        scope.launch {
            globalSettings.settings.collect { settings ->
                mutableState.update { current -> current.copy(globalSettings = settings) }
            }
        }
        scope.launch {
            modelCatalog.models.collect { models ->
                mutableState.update { current -> current.copy(models = models) }
            }
        }
    }

    internal suspend fun initialize() {
        lifecycleMutex.withLock { repository.refresh() }
    }

    public suspend fun refresh() {
        lifecycleMutex.withLock {
            repository.refresh()
            activeRootViewModel()?.refresh()
        }
    }

    /** Refreshes persisted session metadata without refreshing an open Agent tree. */
    public suspend fun refreshSessionCatalog() {
        lifecycleMutex.withLock { repository.refresh() }
    }

    public suspend fun open(sessionIndex: Int) {
        lifecycleMutex.withLock { activate(tabRegistry.openSession(sessionIndex)) }
    }

    public suspend fun selectTab(target: SessionTabTarget) {
        lifecycleMutex.withLock {
            when (target) {
                is SessionTabTarget.NewSession -> {
                    require(target.id in newSessionTabs) { "New session tab ${target.id} is not open." }
                }

                is SessionTabTarget.OpenSession -> {
                    require(target in tabRegistry.tabs) { "Session ${target.sessionIndex} is not open in a tab." }
                }
            }
            activate(tabRegistry.select(target))
        }
    }

    /** Adds one independent, not-yet-materialized New session tab and makes it active. */
    public suspend fun createNewSessionTab() {
        lifecycleMutex.withLock { activate(tabRegistry.addNew(allocateNewSessionTab())) }
    }

    /** Closes one visible tab, discarding a virtual New-session draft without persisting it. */
    public suspend fun closeTab(target: SessionTabTarget) {
        lifecycleMutex.withLock {
            require(target in tabRegistry.tabs) { "Cannot close a tab that is not open." }
            tabRegistry = tabRegistry.close(target) ?: newTabRegistry()
            when (target) {
                is SessionTabTarget.NewSession -> newSessionTabs.remove(target.id)?.close()
                is SessionTabTarget.OpenSession -> repository.close(target.sessionIndex)
            }
            activate(tabRegistry)
        }
    }

    public suspend fun delete(sessionIndex: Int) {
        lifecycleMutex.withLock {
            val target = SessionTabTarget.OpenSession(sessionIndex)
            val nextRegistry = target.takeIf { it in tabRegistry.tabs }
                ?.let { openTab -> tabRegistry.close(openTab) ?: newTabRegistry() }
            if (nextRegistry != null) tabRegistry = nextRegistry
            repository.delete(sessionIndex)
            if (nextRegistry != null) activate(nextRegistry)
        }
    }

    private suspend fun forkLocked(
        source: AgentRuntimeTreeEntry,
        untilExclusive: Int,
    ) {
        val root = repository.fork(source.viewModel.session, untilExclusive)
        val sessionIndex = requireNotNull(repository.state.value.selectedSessionIndex) {
            "Forking a session did not select its new root."
        }
        activate(
            registry = tabRegistry.openSession(sessionIndex),
            openedSession = sessionIndex to root,
        )
    }

    /** Forks the active root tab at its latest stable storage snapshot. */
    public suspend fun forkSelectedSession() {
        lifecycleMutex.withLock {
            val target = requireNotNull(tabRegistry.activeTarget as? SessionTabTarget.OpenSession) {
                "No persisted root session tab is active."
            }
            val tree = requireNotNull(rootViewModel(target.sessionIndex)?.state?.value) {
                "The active root session is not open."
            }
            val root = requireNotNull(tree.agents.firstOrNull { entry -> entry.agentId == tree.rootAgentId }) {
                "The active root session has no root Agent."
            }
            val rootState = root.viewModel.state.value
            require(!rootState.running) { "Cannot fork a running root session." }
            require(rootState.latestIndex >= 0) { "Cannot fork an empty root session." }
            forkLocked(root, untilExclusive = rootState.latestIndex + 1)
        }
    }

    /** Forks the selected Agent at one exact committed history entry. */
    public suspend fun forkHistoryEntry(
        sessionIndex: Int,
        agentId: String,
        storageIndex: Int,
    ) {
        require(storageIndex in 0 until Int.MAX_VALUE) {
            "A history fork target must have a non-negative finite successor."
        }
        lifecycleMutex.withLock {
            val (_, agent) = requireSelectedAgent(sessionIndex, agentId)
            require(!agent.viewModel.state.value.running) {
                "Cannot fork a running Agent."
            }
            require(agent.viewModel.session.runtime.runningTurn.value == null) {
                "Cannot fork an Agent with an active turn job."
            }
            require(agent.viewModel.session.storage.stable.floorToIndex(storageIndex) == storageIndex) {
                "History entry $storageIndex is no longer committed."
            }
            forkLocked(agent, untilExclusive = storageIndex + 1)
        }
    }

    /** Executes and consumes the selected Agent's pending history revert request. */
    public suspend fun confirmHistoryRevert(
        sessionIndex: Int,
        agentId: String,
    ) {
        lifecycleMutex.withLock {
            val (root, agent) = requireSelectedAgent(sessionIndex, agentId)
            val request = agent.viewModel.takeHistoryRevertRequest() ?: return@withLock
            agent.viewModel.revertHistory(request.storageIndex)
            if (agent.agentId == root.state.value.rootAgentId) {
                repository.refresh()
                publishTabs(repository.state.value)
            }
        }
    }

    public fun selectAgent(agentId: String) {
        val target = tabRegistry.activeTarget as? SessionTabTarget.OpenSession ?: return
        rootViewModel(target.sessionIndex)?.selectAgent(agentId)
    }

    /** Renames either a persisted root thread or a virtual New-session tab's draft title. */
    public suspend fun renameSession(target: SessionTabTarget, threadName: String) {
        val normalized = threadName.trim()
        require(normalized.isNotEmpty()) { "A session name cannot be blank." }
        lifecycleMutex.withLock {
            when (target) {
                is SessionTabTarget.NewSession -> {
                    val newSession = requireNotNull(newSessionTabs[target.id]) {
                        "New session tab ${target.id} is not open."
                    }
                    newSession.renameThread(normalized)
                    publishTabs(repository.state.value)
                }

                is SessionTabTarget.OpenSession -> {
                    val root = requireNotNull(rootViewModel(target.sessionIndex)) {
                        "Session ${target.sessionIndex} is not open."
                    }
                    val tree = root.state.value
                    val rootAgent =
                        requireNotNull(tree.agents.firstOrNull { entry -> entry.agentId == tree.rootAgentId }) {
                            "Session ${target.sessionIndex} has no root Agent."
                        }
                    rootAgent.viewModel.renameThread(normalized)
                    repository.refresh()
                    publishTabs(repository.state.value)
                }
            }
        }
    }

    /** Submits to the active Agent or materializes the active New session tab. */
    internal suspend fun submit(content: List<ContentItem>) {
        lifecycleMutex.withLock {
            require(content.isNotEmpty()) { "A submitted turn must contain content." }
            when (val target = tabRegistry.activeTarget) {
                is SessionTabTarget.NewSession -> submitNewSession(target, content)
                is SessionTabTarget.OpenSession -> {
                    val root = requireNotNull(rootViewModel(target.sessionIndex)) {
                        "The active root session is not open."
                    }
                    val tree = root.state.value
                    val selectedAgent = requireNotNull(tree.agents.firstOrNull { entry -> entry.selected }) {
                        "The active root session has no selected Agent."
                    }
                    selectedAgent.viewModel.submit(content)
                }
            }
        }
    }

    /** Materializes a root only for the virtual new-session target's own draft. */
    public suspend fun submitNewSessionComposer(target: SessionTabTarget.NewSession): Boolean {
        return lifecycleMutex.withLock {
            val newSession = requireNotNull(newSessionTabs[target.id]) {
                "New session tab ${target.id} is not open."
            }
            val text = newSession.composer.takeText() ?: return@withLock false
            submitNewSession(target, listOf(ContentItem.InputText(text)))
            true
        }
    }

    /** The active virtual tab's ViewModel, or `null` while a persisted root is active. */
    public fun activeNewSession(): NewSessionViewModel? =
        (tabRegistry.activeTarget as? SessionTabTarget.NewSession)
            ?.let { target -> newSessionTabs[target.id] }

    /** Updates application-wide defaults copied into subsequently created New-session tabs. */
    public suspend fun updateNewSessionDefaults(
        transform: (io.github.stream29.kodex.cli.settings.KodexNewSessionSettings) ->
        io.github.stream29.kodex.cli.settings.KodexNewSessionSettings,
    ) {
        globalSettings.update { settings -> settings.copy(newSession = transform(settings.newSession)) }
    }

    /** Updates the application-wide controls for automatic root-session titles. */
    public suspend fun updateSessionTitleSettings(
        transform: (io.github.stream29.kodex.cli.settings.SessionTitleSettings) ->
        io.github.stream29.kodex.cli.settings.SessionTitleSettings,
    ) {
        globalSettings.update { settings -> settings.copy(sessionTitle = transform(settings.sessionTitle)) }
    }

    /** Updates the application-wide multiline composer policy. */
    public suspend fun updateNewLineKey(newLineKey: io.github.stream29.kodex.cli.settings.NewLineKey) {
        globalSettings.update { settings -> settings.copy(newLineKey = newLineKey) }
    }

    /** Changes the persisted owner of the active subscription credentials. */
    public suspend fun updateAuthSource(source: io.github.stream29.kodex.cli.settings.KodexAuthSource) {
        globalSettings.update { settings -> settings.copy(authSource = source) }
    }

    public fun requestExit() {
        mutableState.update { current -> current.copy(exitRequested = true) }
    }

    public override fun close() {
        selectedTreeCollection?.cancel()
        newSessionTabs.values.forEach(NewSessionViewModel::close)
        newSessionTabs.clear()
        repository.close()
        scope.cancel()
    }

    private suspend fun submitNewSession(
        target: SessionTabTarget.NewSession,
        content: List<ContentItem>,
    ) {
        val newSession = requireNotNull(newSessionTabs[target.id]) {
            "New session tab ${target.id} is not open."
        }
        val hasExplicitTitle = newSession.state.value.threadName != null
        val root = newSession.create()
        val sessionIndex = requireNotNull(repository.state.value.selectedSessionIndex) {
            "Creating a session did not select its new root."
        }
        val registry = tabRegistry.materialize(target, sessionIndex)
        newSessionTabs.remove(target.id)
        activate(registry, openedSession = sessionIndex to root)
        newSession.close()
        val rootAgent = requireNotNull(root.state.value.agents.firstOrNull { entry ->
            entry.agentId == root.state.value.rootAgentId
        }) {
            "The newly created root session has no root Agent."
        }
        if (hasExplicitTitle) rootAgent.viewModel.suppressAutomaticTitle()
        rootAgent.viewModel.submit(content)
    }

    private fun allocateNewSessionTab(): SessionTabTarget.NewSession {
        val target = SessionTabTarget.NewSession(
            id = nextNewSessionTabId++,
            ordinal = nextNewSessionOrdinal++,
        )
        newSessionTabs[target.id] = scope.createNewSessionViewModel(
            globalSettings = globalSettings,
            sessions = repository,
            workingDirectory = workingDirectory,
        )
        return target
    }

    private fun newTabRegistry(): SessionTabRegistryState {
        val target = allocateNewSessionTab()
        return SessionTabRegistryState(tabs = listOf(target), activeTarget = target)
    }

    private suspend fun activate(
        registry: SessionTabRegistryState,
        openedSession: Pair<Int, RootSessionViewModel>? = null,
    ) {
        tabRegistry = registry
        val selectedRoot = when (val target = tabRegistry.activeTarget) {
            is SessionTabTarget.NewSession -> {
                repository.clearSelection()
                null
            }

            is SessionTabTarget.OpenSession -> openedSession
                ?.takeIf { (sessionIndex) -> sessionIndex == target.sessionIndex }
                ?.second
                ?: repository.open(target.sessionIndex)
        }
        publishTabs(repository.state.value)
        observeSelectedTree(selectedRoot)
    }

    private fun reconcileRepositoryState(sessions: SessionRepositoryViewState) {
        val openedSessionIndexes = sessions.sessions
            .filter { entry -> entry.viewModel != null }
            .mapTo(mutableSetOf()) { entry -> entry.sessionIndex }
        val nextRegistry = tabRegistry.retainOpenSessions(openedSessionIndexes) ?: newTabRegistry()
        val activeChanged = nextRegistry.activeTarget != tabRegistry.activeTarget
        tabRegistry = nextRegistry
        publishTabs(sessions)
        if (activeChanged) observeSelectedTree(activeRootViewModel(sessions))
    }

    private fun publishTabs(sessions: SessionRepositoryViewState) {
        mutableState.update { current ->
            current.copy(
                sessions = sessions,
                tabs = tabViewStates(sessions),
                activeTab = tabRegistry.activeTarget,
                selectedTree = if (tabRegistry.activeTarget is SessionTabTarget.NewSession) null else current.selectedTree,
            )
        }
    }

    private fun tabViewStates(sessions: SessionRepositoryViewState): List<SessionTabViewState> {
        val entries = sessions.sessions.associateBy { entry -> entry.sessionIndex }
        return tabRegistry.tabs.map { target ->
            SessionTabViewState(
                target = target,
                selected = target == tabRegistry.activeTarget,
                rootSession = (target as? SessionTabTarget.OpenSession)
                    ?.let { open -> entries[open.sessionIndex] },
                newSessionName = (target as? SessionTabTarget.NewSession)
                    ?.let { newSession -> newSessionTabs[newSession.id]?.state?.value?.threadName },
            )
        }
    }

    private fun activeRootViewModel(
        sessions: SessionRepositoryViewState = repository.state.value,
    ): RootSessionViewModel? =
        (tabRegistry.activeTarget as? SessionTabTarget.OpenSession)
            ?.let { target -> rootViewModel(target.sessionIndex, sessions) }

    private fun rootViewModel(
        sessionIndex: Int,
        sessions: SessionRepositoryViewState = repository.state.value,
    ): RootSessionViewModel? =
        sessions.sessions.firstOrNull { entry -> entry.sessionIndex == sessionIndex }?.viewModel

    private fun requireSelectedAgent(
        sessionIndex: Int,
        agentId: String,
    ): Pair<RootSessionViewModel, AgentRuntimeTreeEntry> {
        require(tabRegistry.activeTarget == SessionTabTarget.OpenSession(sessionIndex)) {
            "Session $sessionIndex is no longer active."
        }
        val root = requireNotNull(rootViewModel(sessionIndex)) {
            "Session $sessionIndex is not open."
        }
        val tree = root.state.value
        require(tree.selectedAgentId == agentId) {
            "Agent $agentId is no longer selected."
        }
        val agent = requireNotNull(tree.agents.firstOrNull { entry -> entry.agentId == agentId }) {
            "Agent $agentId does not belong to session $sessionIndex."
        }
        return root to agent
    }

    private fun observeSelectedTree(selected: RootSessionViewModel?) {
        selectedTreeCollection?.cancel()
        mutableState.update { current -> current.copy(selectedTree = selected?.state?.value) }
        if (selected == null) {
            return
        }
        val observedTarget = tabRegistry.activeTarget
        selectedTreeCollection = scope.launch {
            selected.state.collect { tree ->
                if (tabRegistry.activeTarget == observedTarget) {
                    mutableState.update { current -> current.copy(selectedTree = tree) }
                }
            }
        }
    }
}
