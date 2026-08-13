package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.DeleteSessionPopupViewModel
import io.github.stream29.kodex.app.application.contract.RenameSessionPopupViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelArguments
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns only application navigation, popup identity, and child lifetimes.
 *
 * Every suspending command is serialized. Child work may suspend while the
 * command lock is held so no later command can reinterpret an index or target
 * against a different navigation snapshot.
 */
internal class ApplicationViewModelImpl(
    private val sessions: PersistedSessionViewModelRegistry,
    private val newSessionFactory: NewSessionViewModelFactory,
    private val catalogFactory: SessionCatalogViewModelFactory,
    private val settingsFactory: SettingsViewModelFactory,
    private val loginFactory: OpenAiLoginViewModelFactory,
    private val newSessionArguments: (ordinal: Int) -> NewSessionViewModelArguments,
) : ApplicationViewModel {
    private val commandMutex = Mutex()
    private var nextDraftOrdinal = 1
    private var closed = false
    private val mutableNavigation = MutableStateFlow(
        ApplicationNavigationState(
            tabs = listOf(createDraft()),
            selectedIndex = 0,
        ),
    )
    private val mutablePopup =
        MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)

    override val navigation: StateFlow<ApplicationNavigationState> =
        mutableNavigation.asStateFlow()
    override val popup: StateFlow<ApplicationPopupState> = mutablePopup.asStateFlow()

    override suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel =
        commandMutex.withLock {
            ensureOpen()
            val current = mutableNavigation.value
            current.tabs.forEachIndexed { index, tab ->
                val persisted = tab as? PersistedSessionViewModel ?: return@forEachIndexed
                if (persisted.sessionIndex == sessionIndex) {
                    mutableNavigation.value = current.copy(selectedIndex = index)
                    return@withLock persisted
                }
            }
            val opened = sessions.open(sessionIndex)
            mutableNavigation.value = ApplicationNavigationState(
                tabs = current.tabs + opened,
                selectedIndex = current.tabs.size,
            )
            opened
        }

    override suspend fun selectTab(index: Int): Boolean = commandMutex.withLock {
        ensureOpen()
        val current = mutableNavigation.value
        if (index !in current.tabs.indices) return@withLock false
        if (index != current.selectedIndex) {
            mutableNavigation.value = current.copy(selectedIndex = index)
        }
        true
    }

    override suspend fun createNewSessionTab(): NewSessionViewModel = commandMutex.withLock {
        ensureOpen()
        val created = createDraft()
        val current = mutableNavigation.value
        mutableNavigation.value = ApplicationNavigationState(
            tabs = current.tabs + created,
            selectedIndex = current.tabs.size,
        )
        created
    }

    override suspend fun closeTab(target: SessionViewModel): Boolean = commandMutex.withLock {
        ensureOpen()
        val current = mutableNavigation.value
        val index = current.tabs.indexOfFirst { child -> child === target }
        if (index < 0) return@withLock false
        closePopupOwnedBy(target)
        val remaining = current.tabs.toMutableList().apply { removeAt(index) }
        if (remaining.isEmpty()) remaining += createDraft()
        val selectedIndex = when {
            current.selectedIndex < index -> current.selectedIndex
            current.selectedIndex > index -> current.selectedIndex - 1
            else -> index.coerceAtMost(remaining.lastIndex)
        }
        mutableNavigation.value = ApplicationNavigationState(remaining, selectedIndex)
        when (target) {
            is PersistedSessionViewModel -> sessions.release(target.sessionIndex)
            is NewSessionViewModel -> target.close()
        }
        true
    }

    override suspend fun deleteSession(sessionIndex: Int): Boolean = commandMutex.withLock {
        ensureOpen()
        deleteSessionLocked(sessionIndex)
    }

    override suspend fun materializeNewSession(
        tabIndex: Int,
    ): PersistedSessionViewModel = commandMutex.withLock {
        ensureOpen()
        val current = mutableNavigation.value
        require(tabIndex in current.tabs.indices) {
            "New Session tab index $tabIndex is outside the current navigation snapshot."
        }
        val draft = requireNotNull(current.tabs[tabIndex] as? NewSessionViewModel) {
            "Tab $tabIndex is not a New Session."
        }
        val persisted = draft.materialize()
        closePopupOwnedBy(draft)
        val replacement = current.tabs.toMutableList().apply { set(tabIndex, persisted) }
        mutableNavigation.value = ApplicationNavigationState(
            tabs = replacement,
            selectedIndex = current.selectedIndex,
        )
        draft.close()
        persisted
    }

    override suspend fun openSessionCatalogPopup(): ApplicationPopupState.SessionCatalog =
        commandMutex.withLock {
            ensureOpen()
            installPopup(ApplicationPopupState.SessionCatalog(catalogFactory.create()))
        }

    override suspend fun openSettingsPopup(
        target: SessionViewModel,
        initialPage: SettingsPage,
    ): ApplicationPopupState.Settings = commandMutex.withLock {
        ensureOpen()
        requireOwned(target)
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
        target: SessionViewModel,
    ): ApplicationPopupState.RenameSession = commandMutex.withLock {
        ensureOpen()
        requireOwned(target)
        installPopup(
            ApplicationPopupState.RenameSession(RenameSessionPopupViewModelImpl(target)),
        )
    }

    override suspend fun openDeleteSessionPopup(
        sessionIndex: Int,
    ): ApplicationPopupState.DeleteSession = commandMutex.withLock {
        ensureOpen()
        val target = mutableNavigation.value.tabs
            .filterIsInstance<PersistedSessionViewModel>()
            .firstOrNull { child -> child.sessionIndex == sessionIndex }
        installPopup(
            ApplicationPopupState.DeleteSession(
                DeleteSessionPopupViewModelImpl(
                    sessionIndex = sessionIndex,
                    threadName = target?.name?.value,
                    deleteCommand = ::deleteSessionFromPopup,
                ),
            ),
        )
    }

    override suspend fun openLoginPopup(): ApplicationPopupState.Login =
        commandMutex.withLock {
            ensureOpen()
            installPopup(ApplicationPopupState.Login(loginFactory.create()))
        }

    override fun dismissPopup(expected: ApplicationPopupState.Open): Boolean {
        val current = mutablePopup.value
        if (current !== expected) return false
        if (!mutablePopup.compareAndSet(current, ApplicationPopupState.Closed)) return false
        current.closeChild()
        return true
    }

    override suspend fun shutdown() = commandMutex.withLock {
        if (closed) return@withLock
        closeOwnedResources()
        sessions.shutdown()
    }

    override fun close() {
        closeOwnedResources()
    }

    private fun createDraft(): NewSessionViewModel {
        val ordinal = nextDraftOrdinal
        check(ordinal < Int.MAX_VALUE) { "New Session ordinals are exhausted." }
        nextDraftOrdinal += 1
        return newSessionFactory.create(newSessionArguments(ordinal))
    }

    private fun <T : ApplicationPopupState.Open> installPopup(created: T): T {
        val replaced = mutablePopup.value
        mutablePopup.value = created
        (replaced as? ApplicationPopupState.Open)?.closeChild()
        return created
    }

    private fun closePopupOwnedBy(target: SessionViewModel) {
        val current = mutablePopup.value as? ApplicationPopupState.Open ?: return
        val ownsTarget = when (current) {
            is ApplicationPopupState.Settings -> current.target === target
            is ApplicationPopupState.RenameSession -> current.viewModel.target === target
            is ApplicationPopupState.DeleteSession,
            is ApplicationPopupState.Login,
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
        val removed = current.tabs.filterIsInstance<PersistedSessionViewModel>()
            .filter { child -> child.sessionIndex == sessionIndex }
        removed.forEach(::closePopupOwnedBy)
        val deleted = sessions.delete(sessionIndex)
        if (!deleted) return false
        val remaining = current.tabs.filterNot { child ->
            child is PersistedSessionViewModel && child.sessionIndex == sessionIndex
        }.toMutableList()
        if (remaining.isEmpty()) remaining += createDraft()
        val selectedChild = current.selected
        val nextSelection = when {
            selectedChild in remaining -> remaining.indexOf(selectedChild)
            else -> current.selectedIndex.coerceAtMost(remaining.lastIndex)
        }
        mutableNavigation.value = ApplicationNavigationState(remaining, nextSelection)
        closeDeletePopupFor(sessionIndex)
        return true
    }

    private fun requireOwned(target: SessionViewModel) {
        require(mutableNavigation.value.tabs.any { child -> child === target }) {
            "Popup target is not owned by this application."
        }
    }

    private fun closeOwnedResources() {
        if (closed) return
        closed = true
        (mutablePopup.value as? ApplicationPopupState.Open)?.closeChild()
        mutablePopup.value = ApplicationPopupState.Closed
        mutableNavigation.value.tabs.asReversed().forEach(SessionViewModel::close)
    }

    private fun ensureOpen() {
        check(!closed) { "Application ViewModel is closed." }
    }
}

private class RenameSessionPopupViewModelImpl(
    override val target: SessionViewModel,
) : RenameSessionPopupViewModel {
    private val mutableDraftName = MutableStateFlow(target.name.value)
    private var closed = false
    override val draftName: StateFlow<String> = mutableDraftName.asStateFlow()

    override fun updateDraftName(name: String) {
        if (!closed) mutableDraftName.value = name
    }

    override suspend fun rename() {
        check(!closed) { "Rename Session popup is closed." }
        target.rename(mutableDraftName.value.trim())
    }

    override fun close() {
        closed = true
    }
}

private class DeleteSessionPopupViewModelImpl(
    override val sessionIndex: Int,
    override val threadName: String?,
    private val deleteCommand: suspend (Int) -> Boolean,
) : DeleteSessionPopupViewModel {
    private var closed = false

    override suspend fun delete(): Boolean {
        check(!closed) { "Delete Session popup is closed." }
        return deleteCommand(sessionIndex)
    }

    override fun close() {
        closed = true
    }
}

private fun ApplicationPopupState.Open.closeChild() {
    when (this) {
        is ApplicationPopupState.DeleteSession -> viewModel.close()
        is ApplicationPopupState.Login -> viewModel.close()
        is ApplicationPopupState.RenameSession -> viewModel.close()
        is ApplicationPopupState.SessionCatalog -> viewModel.close()
        is ApplicationPopupState.Settings -> viewModel.close()
    }
}
