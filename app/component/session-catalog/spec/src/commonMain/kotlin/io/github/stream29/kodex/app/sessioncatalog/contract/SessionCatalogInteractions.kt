package io.github.stream29.kodex.app.sessioncatalog.contract

import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import kotlinx.coroutines.CancellationException

/**
 * Navigation capabilities bound to one exact catalog popup opening.
 *
 * The host injects a dismissal closure capturing its exact open handle, never a
 * lookup of the currently visible popup. It also owns Session registry failure
 * reporting and root tab cleanup. These capabilities do not own backend resources.
 */
public interface SessionCatalogInteractions {
    /**
     * Opens/selects the captured persisted index. Success means navigation completed.
     * Does not dismiss the catalog; the component calls [dismissPopup] only afterwards.
     *
     * @throws CancellationException when the caller or catalog is cancelled.
     * @throws Exception when registry initialization/navigation fails; the host reports it.
     */
    public suspend fun openSession(sessionIndex: Int): Unit

    /**
     * Dismisses only the opening captured by this port. A replaced opening is a no-op.
     * No backend close, rollback, deletion or navigation is performed.
     */
    public fun dismissPopup(): Unit
}

/**
 * Exact confirmation owned by one catalog VM. Compare handles by reference, not index.
 *
 * [target] is the row snapshot captured at request time; [viewModel] is the existing
 * Session Delete component, not mirrored delete state. Only owner-issued handles
 * can be dismissed by that owner. Replacement/owner close rejects new child calls;
 * an already accepted backend command is not rolled back.
 *
 * The child's delete command also throws IllegalStateException if its queued
 * handle became stale before dispatch, CancellationException when its caller or
 * catalog closes, and the original dependency/reload exception on failure.
 * True after successful reload removes this exact handle; false/failure retains it
 * unless independently dismissed/replaced. Rendering never creates a second child.
 */
public class SessionCatalogDeleteHandle(
    public val target: SessionCatalogEntry,
    public val viewModel: SessionDeleteViewModel,
)
