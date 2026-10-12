package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Common UI-facing ViewModel implemented by a virtual or persisted Session.
 *
 * This contract only unifies frontend presentation and root-settings commands.
 * It does not give both variants the same persistence identity or lifecycle.
 * Concrete component contracts may live in separate modules; sharing this presentation
 * interface must not require moving their declarations back into one sealed root project.
 *
 * Read name/settings from the captured child, not a root-level mirror. Settings
 * commands retain [AgentSettingsViewModel] admission and cancellation semantics.
 * Unmounting a renderer or selecting another tab does not close this handle.
 */
public interface SessionViewModel :
    AgentSettingsViewModel,
    AutoCloseable {
    /** Root/tab display name; virtual drafts and persisted settings may derive it differently. */
    public val name: StateFlow<String>

    /**
     * Renames this captured Session, not the latest selected tab.
     * @throws IllegalStateException if its current binding is not ready.
     * @throws Exception if the owner-specific rename/storage/transport operation fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting or this local
     * owner is cancelled; already accepted backend work is not undone.
     */
    public suspend fun rename(name: String): Unit

    /**
     * Idempotently releases this handle's local observations and drafts.
     * A persisted handle withdraws its borrowed Agent through the owning RPC view;
     * it does not independently close a second Agent owner, Stop backend work,
     * delete data, or close shared transport. Virtual closure follows its child contract.
     */
    override fun close(): Unit
}
