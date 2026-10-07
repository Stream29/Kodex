package io.github.stream29.kodex.app.authenticationsettings

import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Host-owned read sources and capabilities, not settings/store/RPC handles. Source and summary
 * are independent streams, not an atomic account snapshot. Closing the child only releases its
 * observations/waiters; it never closes these sources or the application write queue.
 */
public interface AuthenticationSettingsDependencies {
    /** Current settings source; value is also read at confirmation COMMAND start. */
    public val selectedSource: StateFlow<KodexAuthSource>
    /** Selected-source summary containing no tokens. May lag a source change. */
    public val authentication: StateFlow<SettingsAuthenticationState>
    /** Application-owned failure flag; survives Settings closing. */
    public val operationFailure: StateFlow<Boolean>

    /**
     * Admit a source write to the existing application queue. True is admission, not durable
     * success. False is rejection, not a failure. Already admitted writes outlive child close.
     * The host coordinates reset dismissal and CAS baselines; the child never mutates its source.
     *
     * @throws CancellationException if admission is cancelled.
     * @throws Exception if synchronous admission fails.
     */
    public fun updateSource(source: KodexAuthSource): Boolean

    /**
     * Remove the captured source only. Missing credentials are success; do not change settings,
     * the other source, or revoke remotely. Cancellation/lost replies do not prove no effect.
     *
     * @throws CancellationException if the command wait is cancelled.
     * @throws Exception if removal fails; do not blindly replay.
     */
    public suspend fun remove(source: KodexAuthSource): Unit

    /**
     * Send a Unit login-opening intent, NOT a source-bound request. The existing host creates
     * Login and captures its source there; no OAuth/listener/browser lifecycle is owned here.
     *
     * @throws CancellationException if intent delivery is cancelled.
     * @throws Exception if intent delivery fails.
     */
    public fun openLogin(): Unit

    /** Record a non-cancellation failure at the shared authority without logging credentials. Must not throw. */
    public fun reportFailure(failure: Throwable): Unit
    /** Acknowledge the shared failure. Must not throw; does not undo any command. */
    public fun dismissFailure(): Unit
}

/**
 * Identity of one visible confirmation. Deliberately has no captured source. Render the live
 * [AuthenticationSettingsState.selectedSource]; delayed callbacks must carry this exact object.
 */
public class AuthenticationLogoutConfirmation

/**
 * Complete renderer projection. Render nothing when closed. A non-null confirmation offers
 * Cancel (initial focus) and Log out with source-specific local-file wording. Source updates
 * change its displayed target but not its identity. Operation progress/failure is not evidence
 * of the authentication state; only the shared summary changes that. Render the shared failure
 * once, either here or in the Settings host, not both.
 */
public data class AuthenticationSettingsState(
    public val selectedSource: KodexAuthSource,
    public val authentication: SettingsAuthenticationState,
    public val operation: SettingsAuthenticationOperationState = SettingsAuthenticationOperationState.Idle,
    public val confirmation: AuthenticationLogoutConfirmation? = null,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
)

/**
 * Settings authentication interaction, confined to the owner's interaction dispatcher.
 * Commands after close are no-ops. Ordinary dependency errors are reported once to the host;
 * CancellationException is not reported. Suspended removal cancellation returns local progress
 * to Idle when still open, without claiming rollback. No automatic login/remove retries.
 *
 * UI disables source/login/logout during SigningOut. This is NOT a global source or login
 * command lock: existing programmatic source writes and login intents remain admitted.
 */
public interface AuthenticationSettingsViewModel : AutoCloseable {
    /** Safe summary, VM-owned confirmation and operation; no parent mirroring. */
    public val state: StateFlow<AuthenticationSettingsState>

    /**
     * Attempt queue admission without optimistic source publication, even during SigningOut.
     * Returns false when closed, rejected, or an ordinary admission error was reported.
     *
     * @throws CancellationException if synchronous admission is cancelled.
     */
    public fun updateSource(source: KodexAuthSource): Boolean
    /**
     * Deliver an unbound login intent, even during SigningOut. Ordinary delivery errors report
     * to the shared authority.
     *
     * @throws CancellationException if synchronous delivery is cancelled.
     */
    public fun requestLogin(): Unit
    /** Open a fresh confirmation unless SigningOut/closed. Does not bind source or remove anything. */
    public fun requestLogout(): Unit
    /**
     * Admit removal once iff expected is the exact visible object and no removal is active.
     * Capture dependencies.selectedSource.value synchronously at this command's start, then
     * clear confirmation and publish SigningOut before launching remove. Subsequent source
     * changes cannot retarget it. Summary is never optimistically changed. Duplicate/old calls
     * are no-ops. Ordinary removal errors publish Failed and report to the shared authority.
     */
    public fun confirmLogout(expected: AuthenticationLogoutConfirmation): Unit
    /** Cancel only the exact visible confirmation; never cancels an accepted remove. */
    public fun cancelLogout(expected: AuthenticationLogoutConfirmation): Unit
    /** Discard unconfirmed intent on navigation away; keep observation and accepted commands. */
    public fun hidePage(): Unit
    /** Clear local Failed and acknowledge shared failure; must not clear SigningOut progress. */
    public fun dismissFailure(): Unit
    /**
     * Idempotently hide confirmation, publish closed and cancel child observation/removal waits.
     * Does not close shared stores, drain/cancel accepted source writes, or roll back backend work.
     */
    override fun close(): Unit
}

/** Construct one child attached to an explicit owner scope; construction observes, never logs in. */
public fun interface AuthenticationSettingsViewModelFactory {
    /** Does not assume ownership of dependency lifecycles or trigger a command. */
    public fun create(
        dependencies: AuthenticationSettingsDependencies,
        ownerScope: CoroutineScope,
    ): AuthenticationSettingsViewModel
}
