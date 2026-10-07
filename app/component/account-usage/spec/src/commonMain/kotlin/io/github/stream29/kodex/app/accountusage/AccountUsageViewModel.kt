package io.github.stream29.kodex.app.accountusage

import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Host-owned usage source and capabilities. The child owns neither a usage store nor reset
 * selection/confirmation/consumption. The host alone coordinates navigation refresh and reset
 * opening. Observing/cancelling this child does not close the shared source.
 */
public interface AccountUsageDependencies {
    /** Safe account-isolated projection; authentication/settings streams are not atomically joined. */
    public val usage: StateFlow<SettingsAccountUsageState>
    /** Application-owned operation failure; survives child close and reopening. */
    public val operationFailure: StateFlow<Boolean>

    /**
     * Request supplier usage, never consume credit. Unit completion does not guarantee Available
     * or frontend source synchronization. Source may publish Failed/Unavailable normally.
     *
     * @throws CancellationException if the wait is cancelled; no rollback is implied.
     * @throws Exception if the command fails outside the source's typed failure projection.
     */
    public suspend fun refresh(): Unit

    /**
     * One-way Unit intent to the host's reset child. No credit id, automatic choice, reset state
     * or completion receipt crosses this port. The reset owner validates the current usage.
     *
     * @throws CancellationException if synchronous delivery is cancelled.
     * @throws Exception if intent delivery fails.
     */
    public fun requestReset(): Unit

    /** Report a non-cancellation command failure once at the shared authority; must not throw. */
    public fun reportFailure(failure: Throwable): Unit
    /** Acknowledge the shared failure without changing usage; must not throw. */
    public fun dismissFailure(): Unit
}

/**
 * Renderer projection, retaining null/zero/empty distinctions in the original usage DTO.
 * Render nothing when closed. Actions are hidden for Unavailable. Loading/Redeeming disable UI
 * refresh and reset; only Available with a possible credit enables reset. Count-only snapshots
 * may open the reset owner's details-unavailable prompt, but never silently select a credit.
 * Render a shared operation failure at most once, here or in the host.
 */
public data class AccountUsageState(
    public val usage: SettingsAccountUsageState,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
) {
    /** Presentation-only action visibility; not a global command admission rule. */
    public val actionsVisible: Boolean get() = !closed && usage != SettingsAccountUsageState.Unavailable
    /** Presentation-only refresh enablement; explicit programmatic refreshes are not deduplicated. */
    public val refreshEnabled: Boolean get() = actionsVisible &&
        usage !is SettingsAccountUsageState.Loading && usage !is SettingsAccountUsageState.Redeeming
    /** Reset opens selection only; count and detailed list remain independently nullable. */
    public val resetEnabled: Boolean get() {
        if (closed || usage !is SettingsAccountUsageState.Available) return false
        val credits = usage.snapshotOrNull()?.resetCredits ?: return false
        val detailed = credits.credits.orEmpty()
        val count = credits.availableCount ?: detailed.size.toLong()
        return count > 0L || detailed.isNotEmpty()
    }
}

/**
 * Dependency-only account usage interaction, confined to the owner's interaction dispatcher.
 * Construction only observes; initial/re-entry refresh remains the Settings host's job.
 * No local fallback caching, auth inference, automatic retry or reset dialog exists here.
 * Commands after close are no-ops; ordinary errors report once to the shared authority.
 */
public interface AccountUsageViewModel : AutoCloseable {
    /** Read-only original source state plus derived presentation rules and lifecycle. */
    public val state: StateFlow<AccountUsageState>
    /**
     * Launch one dependency refresh for EVERY active call, including Loading/Redeeming/
     * Unavailable or overlapping calls. UI disablement does not create command-global dedupe.
     * Unit return is launch admission, not a current snapshot. Cancellation is not failure.
     */
    public fun refresh(): Unit
    /**
     * Deliver a one-way intent; the host/reset owner validates it. Do not consume a credit.
     * Ordinary delivery errors report once; no component-side reset workflow is created.
     *
     * @throws CancellationException if synchronous intent delivery is cancelled.
     */
    public fun requestReset(): Unit
    /** Acknowledge the host-owned command failure; do not clear source Failed or refresh implicitly. */
    public fun dismissFailure(): Unit
    /**
     * Idempotently publish closed and cancel child observers/refresh waiters only.
     * Does not close shared sources, cancel a host-owned reset, or undo accepted backend work.
     */
    override fun close(): Unit
}

/** Typed factory with explicit owner lifecycle, no service locator or constructor refresh. */
public fun interface AccountUsageViewModelFactory {
    /** Subscribe to dependencies; no command is dispatched during creation. */
    public fun create(dependencies: AccountUsageDependencies, ownerScope: CoroutineScope): AccountUsageViewModel
}
