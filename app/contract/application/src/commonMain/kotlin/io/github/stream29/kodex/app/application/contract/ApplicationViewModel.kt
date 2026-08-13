package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-level ViewModel contract.
 *
 * It owns opened Session registries, tab navigation, the current exclusive
 * popup child, registry commands, and shutdown. It does not own settings,
 * models, authentication, or Agent execution state.
 *
 * Each popup-opening command creates its child before changing [popup]. A
 * creation failure preserves the current popup. Success atomically replaces the
 * current open handle, then closes the replaced child. Dismissal and shutdown
 * also close popup children owned by this ViewModel.
 */
public interface ApplicationViewModel : AutoCloseable {
    public val navigation: StateFlow<ApplicationNavigationState>
    public val popup: StateFlow<ApplicationPopupState>

    /** Opens or reuses one Session child and selects its tab. */
    public suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel

    /** Returns false when [index] does not address the current tab list. */
    public suspend fun selectTab(index: Int): Boolean

    /** Creates one independent virtual tab and selects it. */
    public suspend fun createNewSessionTab(): NewSessionViewModel

    /**
     * Closes one child handle without deleting persisted Session data.
     *
     * Any current Settings or Rename popup owned by [target] is closed in the
     * same serialized command. Returns false when [target] was already absent.
     */
    public suspend fun closeTab(target: SessionViewModel): Boolean

    /**
     * Deletes one persisted Session and removes any matching open tab.
     *
     * A current Delete popup for [sessionIndex], or a target-owned popup for the
     * matching open child, is also closed.
     */
    public suspend fun deleteSession(sessionIndex: Int): Boolean

    /**
     * Materializes the New Session currently at [tabIndex], then replaces that
     * exact slot in one navigation update and returns the persisted child.
     *
     * An invalid or non-New-Session index, or a child materialization failure,
     * escapes to the caller without changing navigation. Success preserves the
     * list size and selected index, and closes any popup owned by the consumed
     * child.
     */
    public suspend fun materializeNewSession(
        tabIndex: Int,
    ): PersistedSessionViewModel

    /**
     * Creates and opens a popup-scoped catalog child without loading catalog
     * contents. The frontend requests the first refresh from the returned child.
     */
    public suspend fun openSessionCatalogPopup(): ApplicationPopupState.SessionCatalog

    /** Creates and opens one Settings popup bound to the captured [target]. */
    public suspend fun openSettingsPopup(
        target: SessionViewModel,
        initialPage: SettingsPage,
    ): ApplicationPopupState.Settings

    /** Creates and opens one Rename Session popup bound to the exact [target]. */
    public suspend fun openRenameSessionPopup(
        target: SessionViewModel,
    ): ApplicationPopupState.RenameSession

    /** Creates and opens one Delete Session popup for the captured persisted index. */
    public suspend fun openDeleteSessionPopup(
        sessionIndex: Int,
    ): ApplicationPopupState.DeleteSession

    /** Creates and opens one independently disposable OpenAI login child. */
    public suspend fun openLoginPopup(): ApplicationPopupState.Login

    /** Opens a directory picker bound to the exact settings-owning [target]. */
    public suspend fun openWorkingDirectoryPopup(
        target: AgentSettingsViewModel,
    ): ApplicationPopupState.WorkingDirectory

    /**
     * Dismisses and closes [expected] only while it is still the current popup.
     *
     * Implementations compare the current handle with [expected] by referential
     * identity.
     *
     * Returns false after another popup replaced it or it was already closed.
     */
    public fun dismissPopup(expected: ApplicationPopupState.Open): Boolean

    /**
     * Stops new commands, flushes children, closes them in ownership order,
     * and returns after all owned resources are closed. Repeated calls are
     * idempotent.
     */
    public suspend fun shutdown(): Unit

    /** Immediate idempotent cancellation fallback for process disposal. */
    override fun close(): Unit
}
