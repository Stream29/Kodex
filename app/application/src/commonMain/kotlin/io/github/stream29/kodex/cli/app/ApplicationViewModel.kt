package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.application.contract.RenameSessionPopupViewModel
import io.github.stream29.kodex.app.application.contract.WorkingDirectoryPopupViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.createWorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogInteractions
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
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

/**
 * Creates the navigation/popup owner using the same typed ports as the CLI host.
 * The caller owns [ownerScope] and must close and shut down the returned owner.
 * Its CoroutineExceptionHandler is the existing unhandled-error outlet for an
 * accepted submission whose renderer waiter has gone away.
 * No renderer, RPC transport or backend lifetime is created here.
 */
public fun createApplicationViewModel(
    sessions: PersistedSessionViewModelRegistry,
    newSessionFactory: NewSessionViewModelFactory,
    catalogFactory: SessionCatalogViewModelFactory,
    catalogDependencies: SessionCatalogDependencies,
    settingsFactory: SettingsViewModelFactory,
    loginFactory: OpenAiLoginViewModelFactory,
    createDirectoryPicker: (Path) -> DirectoryPickerViewModel,
    newSessionArguments: NewSessionViewModelArguments,
    ownerScope: CoroutineScope,
): ApplicationViewModel = ApplicationViewModelImpl(
    sessions, newSessionFactory, catalogFactory, catalogDependencies,
    settingsFactory, loginFactory, createDirectoryPicker, newSessionArguments,
    ownerScope,
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
    private val catalogFactory: SessionCatalogViewModelFactory,
    private val catalogDependencies: SessionCatalogDependencies,
    private val settingsFactory: SettingsViewModelFactory,
    private val loginFactory: OpenAiLoginViewModelFactory,
    private val createDirectoryPicker: (Path) -> DirectoryPickerViewModel,
    newSessionArguments: NewSessionViewModelArguments,
    ownerScope: CoroutineScope,
) : ApplicationViewModel {
    private val commandMutex = Mutex()
    private val submissionGate = Mutex()
    private val parent = requireNotNull(ownerScope.coroutineContext[Job]) {
        "Application commands require a frontend owner."
    }
    override val newSession: NewSessionViewModel = newSessionFactory.create(newSessionArguments)
    private val commandOwner = SupervisorJob(parent)
    private val commands = CoroutineScope(ownerScope.coroutineContext + commandOwner)
    private val closed = MutableStateFlow(false)
    private val mutableNavigation = MutableStateFlow(
        ApplicationNavigationState(
            tabs = emptyList(),
            selection = ApplicationTabSelection.NewSession,
        ),
    )
    private val mutablePopup =
        MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)

    override val navigation: StateFlow<ApplicationNavigationState> =
        mutableNavigation.asStateFlow()
    override val popup: StateFlow<ApplicationPopupState> = mutablePopup.asStateFlow()

    init {
        commandOwner.invokeOnCompletion { closeOwnedResources() }
    }

    override suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel =
        commandMutex.withLock {
            ensureOpen()
            val current = mutableNavigation.value
            val opened = sessions.open(sessionIndex)
            ensureOpen()
            current.tabs.forEachIndexed { index, tab ->
                if (tab.sessionIndex == sessionIndex) {
                    check(tab === opened) {
                        "Persisted Session registry returned a different handle for index $sessionIndex."
                    }
                    mutableNavigation.value = current.copy(selection = ApplicationTabSelection.SelectedTab(index))
                    return@withLock opened
                }
            }
            mutableNavigation.value = ApplicationNavigationState(
                tabs = current.tabs + opened,
                selection = ApplicationTabSelection.SelectedTab(current.tabs.size),
            )
            opened
        }

    override suspend fun selectTab(target: PersistedSessionViewModel): Boolean = commandMutex.withLock {
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

    override suspend fun closeTab(target: PersistedSessionViewModel): Boolean = commandMutex.withLock {
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
        sessions.archive(target.sessionIndex)
        check(closeTabLocked(target)) {
            "Persisted Session tab disappeared during a serialized close-and-archive command."
        }
        true
    }

    override suspend fun deleteSession(sessionIndex: Int): Boolean = commandMutex.withLock {
        ensureOpen()
        deleteSessionLocked(sessionIndex)
    }

    override suspend fun forkSession(sessionIndex: Int): Int = commandMutex.withLock {
        ensureOpen()
        sessions.fork(sessionIndex)
    }

    override suspend fun openCreatedSessions(sessionIndexes: List<Int>): Unit = commandMutex.withLock {
        ensureOpen()
        for (index in sessionIndexes.distinct()) {
            val current = mutableNavigation.value
            if (current.tabs.any { it.sessionIndex == index }) continue
            val child = sessions.open(index)
            ensureOpen()
            mutableNavigation.value = current.copy(tabs = current.tabs + child)
        }
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
                val current = mutableNavigation.value
                mutableNavigation.value = ApplicationNavigationState(
                    current.tabs + persisted,
                    ApplicationTabSelection.SelectedTab(current.tabs.size),
                )
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

    override suspend fun openSessionCatalogPopup(): ApplicationPopupState.SessionCatalog =
        commandMutex.withLock {
            ensureOpen()
            lateinit var opening: ApplicationPopupState.SessionCatalog
            opening =
                ApplicationPopupState.SessionCatalog(
                    catalogFactory.create(
                        dependencies = object : SessionCatalogDependencies by catalogDependencies {
                            override suspend fun fork(sessionIndex: Int) =
                                forkSession(sessionIndex)

                            override suspend fun delete(sessionIndex: Int) =
                                deleteSession(sessionIndex)
                        },
                        interactions = object : SessionCatalogInteractions {
                            override suspend fun openSession(sessionIndex: Int) {
                                this@ApplicationViewModelImpl.openSession(sessionIndex)
                            }
                            override fun dismissPopup() { this@ApplicationViewModelImpl.dismissPopup(opening) }
                        },
                    ),
                )
            installPopup(opening)
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
            .firstOrNull { child -> child.sessionIndex == sessionIndex }
        installPopup(
            ApplicationPopupState.DeleteSession(
                createSessionDeleteViewModel(
                    sessionIndex = sessionIndex,
                    threadName = target?.name?.value,
                    dependencies = SessionDeleteDependencies(::deleteSessionFromPopup),
                ),
            ),
        )
    }

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
        installPopup(
            ApplicationPopupState.WorkingDirectory(
                ApplicationWorkingDirectoryAdapter(
                    target = target,
                    sessionOwner = sessionOwner,
                    picker = createDirectoryPicker(target.settings.value.cwd),
                    dependencies = WorkingDirectoryDependencies(target::updateWorkingDirectory),
                ),
            ),
        )
    }

    override suspend fun openSuggestedWorkingDirectoryPopup(
        target: AgentViewModel,
        callId: String,
    ): ApplicationPopupState.WorkingDirectory? = commandMutex.withLock {
        ensureOpen()
        val sessionOwner = requireOwned(target)
        val suggestion = target.suggestSubagentTask
        val pending = (suggestion.state.value as? SuggestSubagentTaskState.Pending)
            ?.takeIf { it.callId == callId && !it.submitting }
            ?: return@withLock null
        installPopup(
            ApplicationPopupState.WorkingDirectory(
                ApplicationWorkingDirectoryAdapter(
                    target = target,
                    sessionOwner = sessionOwner,
                    picker = createDirectoryPicker(pending.configuration.cwd),
                    dependencies = bindSuggestedWorkingDirectory(suggestion, callId),
                ),
            ),
        )
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
            else -> current.closeChild()
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

    private suspend fun closeTabLocked(target: PersistedSessionViewModel): Boolean {
        val current = mutableNavigation.value
        val index = current.tabs.indexOfFirst { child -> child === target }
        if (index < 0) return false
        closePopupOwnedBy(target)
        val remaining = current.tabs.toMutableList().apply { removeAt(index) }
        mutableNavigation.value = current.afterRemoval(remaining)
        sessions.release(target.sessionIndex)
        return true
    }

    private fun <T : ApplicationPopupState.Open> installPopup(created: T): T {
        val replaced = mutablePopup.value
        mutablePopup.value = created
        (replaced as? ApplicationPopupState.Open)?.closeChild()
        return created
    }

    private fun closePopupOwnedBy(target: PersistedSessionViewModel) {
        val current = mutablePopup.value as? ApplicationPopupState.Open ?: return
        val ownsTarget = when (current) {
            is ApplicationPopupState.Settings -> current.target === target
            is ApplicationPopupState.RenameSession -> current.viewModel.target === target
            is ApplicationPopupState.WorkingDirectory ->
                (current.viewModel as ApplicationWorkingDirectoryAdapter).sessionOwner === target

            is ApplicationPopupState.Login -> current.returnTo.target === target
            is ApplicationPopupState.DeleteSession,
            is ApplicationPopupState.SessionCatalog,
                -> false
        }
        if (ownsTarget) {
            mutablePopup.value = ApplicationPopupState.Closed
            current.closeChild()
        }
    }

    private fun closeDeletePopupFor(sessionIndex: Int) {
        val current = mutablePopup.value as? ApplicationPopupState.DeleteSession ?: return
        if (current.viewModel.sessionIndex == sessionIndex) {
            mutablePopup.value = ApplicationPopupState.Closed
            current.closeChild()
        }
    }

    private suspend fun deleteSessionFromPopup(sessionIndex: Int): Boolean =
        deleteSession(sessionIndex)

    private suspend fun deleteSessionLocked(sessionIndex: Int): Boolean {
        val current = mutableNavigation.value
        val removed = current.tabs.filter { child -> child.sessionIndex == sessionIndex }
        removed.forEach(::closePopupOwnedBy)
        val deleted = sessions.delete(sessionIndex)
        if (!deleted) return false
        val remaining = current.tabs.filterNot { child ->
            child.sessionIndex == sessionIndex
        }
        mutableNavigation.value = current.afterRemoval(remaining)
        closeDeletePopupFor(sessionIndex)
        return true
    }

    private fun ApplicationNavigationState.afterRemoval(
        remaining: List<PersistedSessionViewModel>,
    ): ApplicationNavigationState {
        val next = when (val selected = selection) {
            ApplicationTabSelection.NewSession -> ApplicationTabSelection.NewSession
            is ApplicationTabSelection.SelectedTab -> {
                val surviving = remaining.indexOfFirst { it === selectedSession }
                when {
                    remaining.isEmpty() -> ApplicationTabSelection.NewSession
                    surviving >= 0 -> ApplicationTabSelection.SelectedTab(surviving)
                    else -> ApplicationTabSelection.SelectedTab(selected.index.coerceAtMost(remaining.lastIndex))
                }
            }
        }
        return ApplicationNavigationState(remaining, next)
    }

    private fun requireOwned(target: PersistedSessionViewModel) {
        require(mutableNavigation.value.tabs.any { child -> child === target }) {
            "Popup target is not owned by this application."
        }
    }

    private fun requireOwned(target: AgentSettingsViewModel): PersistedSessionViewModel? {
        if (target === newSession) return null
        return requireNotNull(mutableNavigation.value.tabs.firstOrNull { child ->
            child === target || child.rootAgent.value === target
        }) { "Working-directory target is not owned by this application." }
    }

    private fun closeOwnedResources() {
        if (!closed.compareAndSet(false, true)) return
        commandOwner.cancel()
        (mutablePopup.value as? ApplicationPopupState.Open)?.closeChild()
        mutablePopup.value = ApplicationPopupState.Closed
        newSession.close()
        mutableNavigation.value.tabs.asReversed().forEach(PersistedSessionViewModel::close)
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

private class ApplicationWorkingDirectoryAdapter(
    override val target: AgentSettingsViewModel,
    // Capture ownership while this exact Agent is current; rebind may later withdraw it.
    val sessionOwner: PersistedSessionViewModel?,
    picker: DirectoryPickerViewModel,
    dependencies: WorkingDirectoryDependencies,
    private val child: WorkingDirectoryViewModel = createWorkingDirectoryViewModel(picker, dependencies),
) : WorkingDirectoryPopupViewModel, WorkingDirectoryViewModel by child

private fun ApplicationPopupState.Open.closeChild() {
    when (this) {
        is ApplicationPopupState.DeleteSession -> viewModel.close()
        is ApplicationPopupState.Login -> {
            viewModel.close()
            returnTo.viewModel.close()
        }
        is ApplicationPopupState.RenameSession -> viewModel.close()
        is ApplicationPopupState.SessionCatalog -> viewModel.close()
        is ApplicationPopupState.Settings -> viewModel.close()
        is ApplicationPopupState.WorkingDirectory -> viewModel.close()
    }
}
