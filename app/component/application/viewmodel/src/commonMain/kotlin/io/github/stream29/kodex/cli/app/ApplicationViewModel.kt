package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.application.contract.AgentPageActionTarget
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationSessionCatalog
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.application.contract.RenameSessionPopupViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.createWorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupActions
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupsViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.createSessionGroupsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelArguments
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

public actual fun createApplicationViewModel(
    sessions: PersistedSessionViewModelRegistry,
    newSessionFactory: NewSessionViewModelFactory,
    settingsFactory: SettingsViewModelFactory,
    loginFactory: OpenAiLoginViewModelFactory,
    createDirectoryPicker: (Path) -> DirectoryPickerViewModel,
    newSessionArguments: NewSessionViewModelArguments,
    ownerScope: CoroutineScope,
    sessionGroups: ApplicationSessionCatalog?,
): ApplicationViewModel = ApplicationViewModelImpl(
    sessions, newSessionFactory,
    settingsFactory, loginFactory, createDirectoryPicker, newSessionArguments,
    ownerScope, sessionGroups,
)

/**
 * Owns only application navigation, popup identity, and child lifetimes.
 *
 * Navigation mutations are serialized. Submission additionally has nonqueueing
 * admission and an Application-owned lifetime. Child work may suspend while the
 * command lock is held so later commands cannot reinterpret a captured target.
 */
internal class ApplicationViewModelImpl(
    private val sessions: PersistedSessionViewModelRegistry,
    newSessionFactory: NewSessionViewModelFactory,
    private val settingsFactory: SettingsViewModelFactory,
    private val loginFactory: OpenAiLoginViewModelFactory,
    private val createDirectoryPicker: (Path) -> DirectoryPickerViewModel,
    newSessionArguments: NewSessionViewModelArguments,
    ownerScope: CoroutineScope,
    private val sessionGroups: ApplicationSessionCatalog? = null,
) : ApplicationViewModel {
    private val commandMutex = Mutex()
    private val historyMutex = Mutex()
    private val submissionGate = Mutex()
    private val parent = requireNotNull(ownerScope.coroutineContext[Job]) {
        "Application commands require a frontend owner."
    }
    override val newSession: NewSessionViewModel = newSessionFactory.create(newSessionArguments)
    private val commandOwner = SupervisorJob(parent)
    private val commands = CoroutineScope(ownerScope.coroutineContext + commandOwner)
    private val lazyCatalog = lazy {
        sessionGroups?.let { reads ->
            createSessionGroupsViewModel(commands, reads, object : SessionGroupActions {
                override suspend fun open(entry: SessionCatalogEntry) = commandMutex.withLock {
                    ensureOpen()
                    val tab = reads.open(entry)
                    ensureOpen()
                    selectOpened(tab)
                    Unit
                }

                override suspend fun rename(entry: SessionCatalogEntry) = commandMutex.withLock {
                    ensureOpen()
                    check(reads.isCurrentRoot(entry)) { "The original Session no longer exists." }
                    val tabOwner = navigation.value.tabs.filterIsInstance<PersistedSessionViewModel>()
                        .firstOrNull { it.agentId == entry.rootAgentId }
                    val child = createSessionRenameViewModel(entry.name.orEmpty(), SessionRenameDependencies { name ->
                        reads.rename(entry, name)
                    })
                    val adapter = object : RenameSessionPopupViewModel, SessionRenameViewModel by child {
                        override val target: PersistedSessionViewModel? = tabOwner
                    }
                    installPopup(ApplicationPopupState.RenameSession(adapter))
                    Unit
                }

                override suspend fun setArchived(entry: SessionCatalogEntry, archived: Boolean) = commandMutex.withLock {
                    ensureOpen()
                    check(reads.isCurrentRoot(entry)) { "The original Session no longer exists." }
                    if (archived) sessions.archive(entry.sessionIndex) else sessions.unarchive(entry.sessionIndex)
                }

                override suspend fun delete(entry: SessionCatalogEntry): Boolean = commandMutex.withLock {
                    ensureOpen()
                    reads.isCurrentRoot(entry) && deleteSessionLocked(entry.sessionIndex)
                }
            })
        }
    }
    override val sessionCatalog: SessionGroupsViewModel? get() = lazyCatalog.value
    private val closed = MutableStateFlow(false)
    private val mutableNavigation = MutableStateFlow(
        ApplicationNavigationState(
            tabs = emptyList(),
            selection = ApplicationTabSelection.NewSession,
        ),
    )
    private val mutablePopup =
        MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
    private var workingDirectoryOwner: Pair<ApplicationPopupState.WorkingDirectory, AgentTabViewModel>? = null

    override val navigation: StateFlow<ApplicationNavigationState> =
        mutableNavigation.asStateFlow()
    override val popup: StateFlow<ApplicationPopupState> = mutablePopup.asStateFlow()

    init {
        commandOwner.invokeOnCompletion { closeOwnedResources() }
    }

    override suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel =
        commandMutex.withLock {
            ensureOpen()
            val opened = sessions.open(sessionIndex)
            ensureOpen()
            selectOpened(opened)
            opened
        }

    override suspend fun openAgent(agentId: Int): AgentTabViewModel {
        val waiter = currentCoroutineContext()
        waiter.ensureActive()
        // Keep ownership of the in-flight local handle even if the renderer cancels
        // its wait. A cancelled caller cannot select the result.
        return commands.async {
            commandMutex.withLock {
                ensureOpen()
                waiter.ensureActive()
                val existing = mutableNavigation.value.tabs.firstOrNull { it.agentId == agentId }
                val opened = existing ?: sessions.openAgent(agentId)
                try {
                    ensureOpen()
                    waiter.ensureActive()
                    selectOpened(opened)
                    opened
                } catch (failure: Throwable) {
                    if (existing == null) withContext(NonCancellable) { sessions.releaseAgent(opened) }
                    throw failure
                }
            }
        }.await()
    }

    private fun selectOpened(opened: AgentTabViewModel, parentAgentId: Int? = null): ApplicationNavigationState {
        val current = mutableNavigation.value
        val index = current.tabs.indexOfFirst { it.agentId == opened.agentId }
        check(index < 0 || current.tabs[index] === opened) { "Registry changed the open handle for Agent ${opened.agentId}." }
        val next = current.copy(
            tabs = if (index < 0) current.tabs + opened else current.tabs,
            selection = ApplicationTabSelection.SelectedTab(if (index < 0) current.tabs.size else index),
            parentAgentIds = if (parentAgentId == null) current.parentAgentIds
                else current.parentAgentIds + (opened.agentId to parentAgentId),
        )
        mutableNavigation.value = next
        return mutableNavigation.value
    }

    override suspend fun enterChild(
        source: AgentPageActionTarget, index: Int, generation: Long, revision: Long, childAgentId: Int,
    ) {
        if (source.tab.agentId == childAgentId) return
        fun matchesRow(): Boolean {
            val parents = navigation.value.parentAgentIds
            val knownParent = parents[childAgentId]
            if (knownParent != null && knownParent != source.tab.agentId) return false
            var ancestor = parents[source.tab.agentId]
            val visited = mutableSetOf<Int>()
            while (ancestor != null && visited.add(ancestor)) {
                if (ancestor == childAgentId) return false
                ancestor = parents[ancestor]
            }
            val history = source.agent.historyIndex
            val window = history.window.value
            return window.revision == revision && history.contains(generation, index) &&
                window.entryAtOrNull(index)?.createdChildAgentId == childAgentId
        }
        navigateFromHistory(source, childAgentId, parentAgentId = source.tab.agentId, beforePublish = {
            (source.tab as? PersistedSessionViewModel)?.requireOriginalRoot()
        }, valid = ::matchesRow)
    }

    override suspend fun returnToParent(source: AgentPageActionTarget, anchor: AgentParentAnchor) {
        val parentId = navigation.value.parentAgentIds[source.tab.agentId] ?: return
        val selected = navigateFromHistory(source, parentId, parentAgentId = null) {
            navigation.value.parentAgentIds[source.tab.agentId] == parentId
        } ?: return
        val parent = requireNotNull(selected.selectedTab)
        val binding = parent.agent.value ?: return
        parent.locateChildEvent(binding, source.tab.agentId, anchor) {
            navigation.value === selected && source.tab.agent.value === source.agent &&
                source.agent.lifecycle.value == AgentLifecycleState.Open && parent.agent.value === binding
        }
    }

    /**
     * Resolve outside the command lock: a slow activation must not block selection or close.
     * Publish only against the original navigation snapshot, including an away-and-back switch.
     */
    private suspend fun navigateFromHistory(
        source: AgentPageActionTarget,
        agentId: Int,
        parentAgentId: Int?,
        beforePublish: suspend () -> Unit = {},
        valid: () -> Boolean,
    ): ApplicationNavigationState? {
        val snapshot = navigation.value
        val waiter = currentCoroutineContext()
        fun isCurrent() = waiter.isActive && navigation.value === snapshot && isCurrentPage(source) && valid()
        return commands.async {
            historyMutex.withLock history@{
                if (!isCurrent()) return@history null
                var opened: AgentTabViewModel? = null
                var retained = false
                try {
                    beforePublish()
                    if (!isCurrent()) return@history null
                    val target = snapshot.tabs.firstOrNull { it.agentId == agentId }
                        ?: sessions.openAgent(agentId).also { opened = it }
                    if (!isCurrent()) return@history null
                    beforePublish()
                    commandMutex.withLock publish@{
                        if (!isCurrent()) return@publish null
                        currentCoroutineContext().ensureActive()
                        selectOpened(target, parentAgentId).also { retained = true }
                    }
                } finally {
                    val unused = opened
                    if (!retained && unused != null) withContext(NonCancellable) {
                        commandMutex.withLock {
                            if (navigation.value.tabs.none { it === unused }) sessions.releaseAgent(unused)
                        }
                    }
                }
            }
        }.await()
    }

    override suspend fun selectTab(target: AgentTabViewModel): Boolean = commandMutex.withLock {
        ensureOpen()
        val current = mutableNavigation.value
        val index = current.tabs.indexOfFirst { child -> child === target }
        if (index < 0) return@withLock false
        if (current.selection != ApplicationTabSelection.SelectedTab(index)) {
            mutableNavigation.value = current.copy(selection = ApplicationTabSelection.SelectedTab(index))
        }
        true
    }

    override suspend fun selectNewSession(): Unit = commandMutex.withLock {
        ensureOpen()
        mutableNavigation.value = mutableNavigation.value.copy(selection = ApplicationTabSelection.NewSession)
    }

    override suspend fun selectNewSessionInDirectory(cwd: Path): Unit = commandMutex.withLock {
        ensureOpen()
        newSession.updateWorkingDirectory(cwd)
        mutableNavigation.value = mutableNavigation.value.copy(selection = ApplicationTabSelection.NewSession)
    }

    override suspend fun closeTab(target: AgentTabViewModel): Boolean = commandMutex.withLock {
        ensureOpen()
        closeTabLocked(target)
    }

    override suspend fun closeAndArchiveSession(
        target: PersistedSessionViewModel,
    ): Boolean = commandMutex.withLock {
        ensureOpen()
        if (mutableNavigation.value.tabs.none { child -> child === target }) {
            return@withLock false
        }
        target.requireOriginalRoot()
        sessions.archive(target.sessionIndex)
        check(closeTabLocked(target)) {
            "Persisted Session tab disappeared during a serialized close-and-archive command."
        }
        true
    }

    private suspend fun deleteSession(sessionIndex: Int): Boolean = commandMutex.withLock {
        ensureOpen()
        deleteSessionLocked(sessionIndex)
    }

    override suspend fun submitNewSession(expectedRevision: Long): PersistedSessionViewModel? {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        if (!submissionGate.tryLock()) return null
        // This Deferred belongs to Application, never to the renderer/caller Job.
        // Completion unlocks admission even if shutdown cancels it before its body starts.
        val accepted = commands.async {
            commandMutex.withLock {
                ensureOpen()
                val input = newSession.composer.state.value
                if (input.revision != expectedRevision || input.text.length == 0) return@withLock null
                val persisted = newSession.submit(expectedRevision) ?: return@withLock null
                currentCoroutineContext().ensureActive()
                ensureOpen()
                selectOpened(persisted)
                persisted
            }
        }
        accepted.invokeOnCompletion { submissionGate.unlock() }
        try {
            return accepted.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // async failures otherwise disappear once the only waiter unmounts.
                // Only the abandoned path installs a reporter: a live waiter receives
                // the original exception through its existing operation-failure path.
                // This observer is also owned/cancelled/joined by Application.
                commands.launch { accepted.await() }
            }
            throw cancelled
        }
    }

    override suspend fun openSettingsPopup(
        target: PersistedSessionViewModel?,
        initialPage: SettingsPage,
    ): ApplicationPopupState.Settings = commandMutex.withLock {
        ensureOpen()
        if (target != null) requireOwned(target)
        installPopup(
            ApplicationPopupState.Settings(
                target = target,
                viewModel = settingsFactory.create(
                    SettingsViewModelArguments(target, initialPage),
                ),
            ),
        )
    }

    override suspend fun openAgentSettingsPopup(target: AgentPageActionTarget): ApplicationPopupState.Settings? =
        commandMutex.withLock {
            ensureOpen()
            if (!isCurrentPage(target)) return@withLock null
            val session = target.tab as? PersistedSessionViewModel
            installPopup(ApplicationPopupState.Settings(
                target = session,
                owner = target.tab,
                viewModel = settingsFactory.create(SettingsViewModelArguments(
                    session, SettingsPage.CurrentSession, target.agent, { isCurrentPage(target) },
                )),
            ))
        }

    override suspend fun openAgentWorkingDirectoryPopup(target: AgentPageActionTarget): ApplicationPopupState.WorkingDirectory? =
        commandMutex.withLock {
            ensureOpen()
            if (!isCurrentPage(target)) return@withLock null
            val agent = target.agent
            val created = ApplicationPopupState.WorkingDirectory(createWorkingDirectoryViewModel(
                picker = createDirectoryPicker(agent.settings.value.cwd),
                dependencies = WorkingDirectoryDependencies { cwd ->
                    if (isCurrentPage(target)) agent.updateWorkingDirectory(cwd)
                },
            ))
            workingDirectoryOwner = created to target.tab
            installPopup(created)
        }

    override suspend fun openRenameSessionPopup(
        target: PersistedSessionViewModel,
    ): ApplicationPopupState.RenameSession = commandMutex.withLock {
        ensureOpen()
        requireOwned(target)
        installPopup(
            ApplicationPopupState.RenameSession(ApplicationSessionRenameAdapter(target)),
        )
    }

    override suspend fun openDeleteSessionPopup(
        sessionIndex: Int,
    ): ApplicationPopupState.DeleteSession = commandMutex.withLock {
        ensureOpen()
        val target = mutableNavigation.value.tabs
            .filterIsInstance<PersistedSessionViewModel>()
            .firstOrNull { child -> child.sessionIndex == sessionIndex }
        installDeleteSessionPopup(sessionIndex, target?.name?.value, ::deleteSession)
    }

    override suspend fun openDeleteSessionPopup(
        target: PersistedSessionViewModel,
    ): ApplicationPopupState.DeleteSession? = commandMutex.withLock {
        ensureOpen()
        if (navigation.value.tabs.none { it === target }) return@withLock null
        installDeleteSessionPopup(target.sessionIndex, target.name.value) { sessionIndex ->
            commandMutex.withLock confirm@{
                ensureOpen()
                if (navigation.value.tabs.none { it === target }) return@confirm false
                target.requireOriginalRoot()
                deleteSessionLocked(sessionIndex)
            }
        }
    }

    private fun installDeleteSessionPopup(
        sessionIndex: Int, name: String?, delete: suspend (Int) -> Boolean,
    ): ApplicationPopupState.DeleteSession =
        installPopup(
            ApplicationPopupState.DeleteSession(
                createSessionDeleteViewModel(
                    sessionIndex = sessionIndex,
                    name = name,
                    dependencies = SessionDeleteDependencies(delete),
                ),
            ),
        )

    override suspend fun openLoginPopup(
        returnTo: ApplicationPopupState.Settings,
    ): ApplicationPopupState.Login =
        commandMutex.withLock {
            ensureOpen()
            check(mutablePopup.value === returnTo) {
                "OpenAI Login can only return to the current Settings popup."
            }
            val created = ApplicationPopupState.Login(
                viewModel = loginFactory.create(),
                returnTo = returnTo,
            )
            mutablePopup.value = created
            created
        }

    override suspend fun openWorkingDirectoryPopup(
        target: AgentSettingsViewModel,
    ): ApplicationPopupState.WorkingDirectory = commandMutex.withLock {
        ensureOpen()
        val sessionOwner = requireOwned(target)
        val created = ApplicationPopupState.WorkingDirectory(
            createWorkingDirectoryViewModel(
                picker = createDirectoryPicker(target.settings.value.cwd),
                dependencies = WorkingDirectoryDependencies(target::updateWorkingDirectory),
            ),
        )
        workingDirectoryOwner = sessionOwner?.let { created to it }
        installPopup(created)
    }

    override fun dismissPopup(expected: ApplicationPopupState.Open): Boolean {
        val current = mutablePopup.value
        if (current !== expected) return false
        val replacement = when (current) {
            is ApplicationPopupState.Login -> current.returnTo
            else -> ApplicationPopupState.Closed
        }
        if (!mutablePopup.compareAndSet(current, replacement)) return false
        when (current) {
            is ApplicationPopupState.Login -> current.viewModel.close()
            else -> closePopupChild(current)
        }
        return true
    }

    override suspend fun shutdown() {
        closeOwnedResources()
        commandOwner.join()
        commandMutex.withLock { sessions.shutdown() }
    }

    override fun close() {
        closeOwnedResources()
    }

    private suspend fun closeTabLocked(target: AgentTabViewModel): Boolean {
        val current = mutableNavigation.value
        val index = current.tabs.indexOfFirst { child -> child === target }
        if (index < 0) return false
        closePopupOwnedBy(target)
        val remaining = current.tabs.toMutableList().apply { removeAt(index) }
        mutableNavigation.value = current.afterRemoval(remaining)
        withContext(NonCancellable) { sessions.releaseAgent(target) }
        return true
    }

    private fun <T : ApplicationPopupState.Open> installPopup(created: T): T {
        val replaced = mutablePopup.value
        mutablePopup.value = created
        (replaced as? ApplicationPopupState.Open)?.let(::closePopupChild)
        return created
    }

    private fun closePopupOwnedBy(target: AgentTabViewModel) {
        val current = mutablePopup.value as? ApplicationPopupState.Open ?: return
        val ownsTarget = when (current) {
            is ApplicationPopupState.Settings -> current.owner === target
            is ApplicationPopupState.RenameSession -> current.viewModel.target === target
            is ApplicationPopupState.WorkingDirectory ->
                workingDirectoryOwner?.let { (popup, owner) -> popup === current && owner === target } == true

            is ApplicationPopupState.Login -> current.returnTo.owner === target
            is ApplicationPopupState.DeleteSession -> false
        }
        if (ownsTarget) {
            mutablePopup.value = ApplicationPopupState.Closed
            closePopupChild(current)
        }
    }

    private fun closeDeletePopupFor(sessionIndex: Int) {
        val current = mutablePopup.value as? ApplicationPopupState.DeleteSession ?: return
        if (current.viewModel.sessionIndex == sessionIndex) {
            mutablePopup.value = ApplicationPopupState.Closed
            closePopupChild(current)
        }
    }

    private suspend fun deleteSessionLocked(sessionIndex: Int): Boolean {
        val current = mutableNavigation.value
        val removed = current.tabs.filter { child -> (child as? PersistedSessionViewModel)?.sessionIndex == sessionIndex }
        removed.forEach(::closePopupOwnedBy)
        val deleted = sessions.delete(sessionIndex)
        if (!deleted) return false
        val remaining = current.tabs.filterNot { child -> removed.any { it === child } }
        mutableNavigation.value = current.afterRemoval(remaining)
        closeDeletePopupFor(sessionIndex)
        return true
    }

    private fun ApplicationNavigationState.afterRemoval(
        remaining: List<AgentTabViewModel>,
    ): ApplicationNavigationState {
        val next = when (val selected = selection) {
            ApplicationTabSelection.NewSession -> ApplicationTabSelection.NewSession
            is ApplicationTabSelection.SelectedTab -> {
                val surviving = remaining.indexOfFirst { it === selectedTab }
                when {
                    remaining.isEmpty() -> ApplicationTabSelection.NewSession
                    surviving >= 0 -> ApplicationTabSelection.SelectedTab(surviving)
                    else -> ApplicationTabSelection.SelectedTab(selected.index.coerceAtMost(remaining.lastIndex))
                }
            }
        }
        // A closed parent may be reopened from a surviving child. Keep only the
        // observed return links reachable from surviving tabs, never a binding.
        val reachable = mutableSetOf<Int>()
        for (tab in remaining) {
            var id: Int? = tab.agentId
            while (id != null && reachable.add(id)) id = parentAgentIds[id]
        }
        return ApplicationNavigationState(remaining, next, parentAgentIds.filterKeys { it in reachable })
    }

    private fun isCurrentPage(target: AgentPageActionTarget): Boolean =
        !closed.value && mutableNavigation.value.selectedTab === target.tab &&
            target.tab.agent.value === target.agent && target.isCurrent()

    private fun requireOwned(target: PersistedSessionViewModel) {
        require(mutableNavigation.value.tabs.any { child -> child === target }) {
            "Popup target is not owned by this application."
        }
    }

    private fun requireOwned(target: AgentSettingsViewModel): AgentTabViewModel? {
        if (target === newSession) return null
        return requireNotNull(mutableNavigation.value.tabs.firstOrNull { child ->
            child === target || child.agent.value === target
        }) { "Working-directory target is not owned by this application." }
    }

    private fun closeOwnedResources() {
        if (!closed.compareAndSet(false, true)) return
        commandOwner.cancel()
        if (lazyCatalog.isInitialized()) lazyCatalog.value?.close()
        (mutablePopup.value as? ApplicationPopupState.Open)?.let(::closePopupChild)
        mutablePopup.value = ApplicationPopupState.Closed
        newSession.close()
        mutableNavigation.value.tabs.asReversed().forEach(AgentTabViewModel::close)
    }

    private fun closePopupChild(popup: ApplicationPopupState.Open) {
        if (workingDirectoryOwner?.first === popup) {
            workingDirectoryOwner = null
        }
        when (popup) {
            is ApplicationPopupState.DeleteSession -> popup.viewModel.close()
            is ApplicationPopupState.Login -> {
                popup.viewModel.close()
                popup.returnTo.viewModel.close()
            }
            is ApplicationPopupState.RenameSession -> popup.viewModel.close()
            is ApplicationPopupState.Settings -> popup.viewModel.close()
            is ApplicationPopupState.WorkingDirectory -> popup.viewModel.close()
        }
    }

    private fun ensureOpen() {
        check(!closed.value) { "Application ViewModel is closed." }
        commandOwner.ensureActive()
    }
}

private class ApplicationSessionRenameAdapter(
    override val target: PersistedSessionViewModel,
    private val child: SessionRenameViewModel = createSessionRenameViewModel(
        target.name.value,
        SessionRenameDependencies(target::rename),
    ),
) : RenameSessionPopupViewModel, SessionRenameViewModel by child
