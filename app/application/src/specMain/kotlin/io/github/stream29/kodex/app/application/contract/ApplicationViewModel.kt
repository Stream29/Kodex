package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-level ViewModel contract.
 *
 * It owns opened Session registries, tab navigation, the current exclusive
 * popup child, registry commands, and shutdown. It does not own settings,
 * models, authentication, or Agent execution state. Implementations receive
 * the original typed Session registry, draft/catalog/Settings/Login factories
 * and directory-picker inputs; no renderer or backend resource is exposed here.
 *
 * Suspending commands serialize admission and navigation mutation. The caller
 * supplies captured child instances, not an index calculated before waiting
 * for admission. Caller cancellation cancels lock waiting or local child
 * waiting, not backend work already accepted; a cancelled/failed command is
 * not automatically retried. Failures propagate rather than becoming stale
 * target results. Earlier completed work in a batch is not rolled back.
 *
 * Each popup-opening command creates its child before changing [popup]. A
 * creation failure preserves the current popup. Success atomically replaces the
 * current open handle, then closes the replaced child. Dismissal and shutdown
 * also close popup children owned by this ViewModel. Render navigation and
 * popup independently and read mutable presentation from their exact children.
 *
 * [close] is the immediate process-disposal fallback; [shutdown] additionally
 * waits for registry cleanup. Closing local tabs does not Stop backend work,
 * delete persisted Sessions, or close the shared RPC transport.
 */
public interface ApplicationViewModel : AutoCloseable {
    /** Atomic tab order/selection; do not mirror child state into this snapshot. */
    public val navigation: StateFlow<ApplicationNavigationState>
    /** Stable reusable child, separate from persisted tabs; never recreated by navigation. */
    public val newSession: NewSessionViewModel
    /** The exclusive overlay; children are borrowed by its renderer. */
    public val popup: StateFlow<ApplicationPopupState>

    /**
     * Opens or reuses one Session child, unarchives it, and selects its tab.
     * @throws IllegalStateException if the Application is closed or the registry
     * violates stable-handle reuse.
     * @throws Exception if opening/unarchiving fails; navigation remains unchanged.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled.
     */
    public suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel

    /**
     * Selects the captured [target] at its current position after acquiring admission.
     *
     * Membership is referential (`===`), not name/index/value equality. A moved
     * but still-owned child is selected in its current slot. Returns false if
     * the instance has been removed; does not select its replacement.
     * No child is closed and no backend command is issued.
     *
     * @throws IllegalStateException if the Application is closed.
     * @throws kotlinx.coroutines.CancellationException if cancelled while waiting
     * for admission; no selection is made by that waiting call.
     */
    public suspend fun selectTab(target: PersistedSessionViewModel): Boolean

    /**
     * Selects the existing singleton without creating a backend Session or a child.
     * @throws IllegalStateException if closed.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun selectNewSession(): Unit

    /**
     * Closes one child handle without deleting persisted Session data.
     *
     * A popup owned by [target], including Login's retained Settings or an
     * Agent-bound directory popup, is closed in the same serialized command.
     * Returns false when [target] was already absent. Closing the last tab
     * selects the existing New Session child. A surviving selected instance stays
     * selected; removing a background tab while on New Session does not switch pages.
     *
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if local release fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled;
     * navigation already updated before a release wait is not rolled back.
     */
    public suspend fun closeTab(target: PersistedSessionViewModel): Boolean

    /**
     * Archives one exact persisted child and closes its tab after Archive succeeds.
     * Returns false when [target] is no longer owned; Archive is not Stop or Delete.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if archive or local release fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun closeAndArchiveSession(target: PersistedSessionViewModel): Boolean

    /**
     * Deletes one persisted Session and removes any matching open tab on success.
     *
     * A current Delete popup for [sessionIndex], or a target-owned popup for the
     * matching open child, is also closed. Returns false if persisted data was
     * absent; storage/transport failures are not absence.
     *
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if deletion or local cleanup fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled;
     * accepted backend deletion is not undone.
     */
    public suspend fun deleteSession(sessionIndex: Int): Boolean

    /**
     * Forks one complete persisted root Session without changing navigation.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if source admission, storage, or transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun forkSession(sessionIndex: Int): Int

    /**
     * Adds already-created child tabs without repeating creation or changing selection.
     * Duplicated indexes already opened by this Application are skipped.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if opening a child fails; previous additions remain.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun openCreatedSessions(sessionIndexes: List<Int>): Unit

    /**
     * Submits the singleton's exact composer revision, then appends/selects the result.
     * Zero-length, stale and in-progress duplicate events return null without effects.
     * Admission rejects duplicates before waiting for the application command mutex;
     * revision is checked again at the mutation boundary.
     *
     * Accepted creation, append, resume admission, reset and navigation belong to
     * the surviving frontend command owner, not the renderer's waiting coroutine.
     * Cancelling that wait does not cancel accepted work. A live waiter receives the
     * original failure; an abandoned wait routes failures through the owner's existing
     * CoroutineExceptionHandler (the host's reportUnhandledError outlet), never both.
     * The singleton is not closed. Failure does not publish partial results, recover,
     * retry, delete the created entity or navigate.
     *
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if creation, storage, opening, or
     * transport fails; the actual failure escapes without catch-all conversion.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled;
     * backend work already accepted is not cancelled or automatically retried.
     */
    public suspend fun submitNewSession(expectedRevision: Long): PersistedSessionViewModel?

    /**
     * Creates a popup-scoped catalog without loading it; caller requests first refresh.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if child creation fails, preserving the current popup.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openSessionCatalogPopup(): ApplicationPopupState.SessionCatalog

    /**
     * Opens Settings for the exact currently-owned [target], or no Session for null.
     * @throws IllegalArgumentException if target is no longer owned.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if child creation fails, preserving the current popup.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openSettingsPopup(
        target: PersistedSessionViewModel?,
        initialPage: SettingsPage,
    ): ApplicationPopupState.Settings

    /**
     * Opens a Rename child for the exact currently-owned [target].
     * @throws IllegalArgumentException if target is no longer owned.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if child creation fails, preserving the current popup.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openRenameSessionPopup(
        target: PersistedSessionViewModel,
    ): ApplicationPopupState.RenameSession

    /**
     * Opens a Delete child for the captured persisted index, even without an open tab.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if child creation fails, preserving the current popup.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openDeleteSessionPopup(
        sessionIndex: Int,
    ): ApplicationPopupState.DeleteSession

    /**
     * Opens independently disposable Login, retaining the exact Settings [returnTo].
     * Dismissal restores that same Settings child; target closure closes both.
     * @throws IllegalStateException if closed or returnTo is not the current popup.
     * @throws Exception if Login creation fails, preserving Settings.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openLoginPopup(
        returnTo: ApplicationPopupState.Settings,
    ): ApplicationPopupState.Login

    /**
     * Opens a directory picker for the singleton, an owned Session or its current Agent.
     * @throws IllegalArgumentException if target is no longer owned/current.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if child creation fails, preserving the current popup.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openWorkingDirectoryPopup(
        target: AgentSettingsViewModel,
    ): ApplicationPopupState.WorkingDirectory

    /**
     * Edits only the captured suggestion call's batch cwd, not the source Session.
     * Returns null if this call is absent/replaced/submitting; merges into its latest draft.
     * @throws IllegalArgumentException if the Agent target is no longer owned/current.
     * @throws IllegalStateException if the Application is closed.
     * @throws Exception if child creation fails, preserving the current popup.
     * @throws kotlinx.coroutines.CancellationException if cancelled during admission.
     */
    public suspend fun openSuggestedWorkingDirectoryPopup(
        target: AgentViewModel,
        callId: String,
    ): ApplicationPopupState.WorkingDirectory?

    /**
     * Dismisses/closes [expected] only while it is the current exact open instance.
     * Returns false after replacement, dismissal, or owner close. Login dismissal
     * closes only Login and restores its retained Settings. This synchronous
     * operation does not launch work or depend on the caller's coroutine lifetime.
     */
    public fun dismissPopup(expected: ApplicationPopupState.Open): Boolean

    /**
     * Stops new commands, cancels/joins accepted local commands, closes the singleton,
     * popup and tab children, and waits for registry cleanup. Repeated calls are
     * idempotent. The host must
     * invoke this in its cleanup scope even after renderer/caller cancellation.
     * Does not Stop accepted backend work or release the host Home/transport.
     *
     * @throws Exception if child/registry cleanup fails; the host preserves its
     * primary failure and records cleanup failure as suppressed where applicable.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled
     * while awaiting admission/cleanup; [close] remains the immediate fallback.
     */
    public suspend fun shutdown(): Unit

    /** Immediate idempotent local close fallback; suspending cleanup uses [shutdown]. */
    override fun close(): Unit
}
