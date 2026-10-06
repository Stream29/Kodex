package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.app.accountusage.AccountUsageViewModel
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Host overlay intent, not an OAuth URL or a source-bound authentication command. */
public sealed interface GlobalSettingsEffect {
    /** The host creates the existing Login child and captures its source at creation time. */
    public data object OpenLogin : GlobalSettingsEffect
}

/**
 * Composition owner for Settings' stable global children.
 *
 * Each child owns its state and commands according to its component spec. This parent does not
 * mirror those states, expose mutable stores, or forward a second set of editing/reset commands.
 * Backend observations, frontend persistence and accepted application writes are host-owned;
 * disposing the popup is not backend shutdown or cancellation of an accepted settings write.
 *
 * Construction binds the actual global configuration/authentication/usage observations, frontend
 * preferences/Hook persistence and application-scope write admission to existing typed child
 * ports. Account Usage's reset intent opens [usageReset]; authentication login intent emits
 * [GlobalSettingsEffect.OpenLogin]. No child constructor triggers an extra usage refresh.
 * Commands and disposal use the owner's interaction dispatcher. Stable child identity survives
 * page changes and Login round trips; page hide is not child close.
 *
 * Render all child state branches and exact dialog handles according to their specs. The root
 * disables duplicate child banners and renders [operationFailure] once, without remote details.
 * Ordinary async failures retain their child/shared failure semantics; cancellation is not a
 * business failure. Write acceptance freezes payload/baseline and means admission only, not
 * successful persistence, rollback, retries or flow convergence. An accepted write may finish
 * after popup close. Session-specific revision/CAS waits do not use this global admission policy.
 */
public interface GlobalSettingsViewModel : AutoCloseable {
    /** Global context roots, custom-source drafts and exact add-dialog interaction. */
    public val contextSourceSettings: ContextSourceSettingsViewModel
    /** Automatic-title configuration, not the generator or Session rename operation. */
    public val sessionTitleSettings: SessionTitleSettingsViewModel
    /** Runtime sidebar widths and persisted paired input keys, not sidebar navigation. */
    public val applicationPreferences: ApplicationPreferencesViewModel
    /** Source choice, safe authentication summary, login intent and local logout confirmation. */
    public val authenticationSettings: AuthenticationSettingsViewModel
    /** Independent usage summary/refresh and a one-way intent to open the reset child. */
    public val accountUsage: AccountUsageViewModel
    /** Sole authority for credit selection, exact second confirmation and consumption dialogs. */
    public val usageReset: UsageResetViewModel
    /** MCP management dialogs/drafts; shared backend clients are not child-owned. */
    public val mcpSettings: McpSettingsViewModel
    /** Local notification configuration; does not execute Hook commands. */
    public val hookSettings: HookSettingsViewModel

    /**
     * Single Settings-host consumer for buffered overlay intents; never broadcasts credentials
     * or URLs. The host creates Login on intent and retains the Settings owner behind it.
     * Closing terminates production; cancelling collection does not stop the shared backend.
     */
    public val effects: Flow<GlobalSettingsEffect>
    /** Shared application failure authority; render once and retain across popup reopening. */
    public val operationFailure: StateFlow<Boolean>
    /**
     * Acknowledge the shared flag without rolling back commands or closing a child.
     * This is not a retry, remote error dismissal or reset of child business state.
     *
     * @throws Throwable if the bound failure acknowledgement throws; it is not swallowed here.
     */
    public fun dismissOperationFailure(): Unit

    /**
     * Idempotently reject new edits and close child observations/transient operations.
     * Mark closed first, then close context sources, title, preferences, authentication, usage,
     * Reset, MCP and Hooks in that order. Only after these succeed, cancel the composition scope,
     * close the overlay intent channel and stop queue admission. Already admitted writes drain in
     * application order before dependent MCP resources close; return is not a drain acknowledgement.
     * Does not close backend stores or promise rollback of a remote command whose wait was cancelled.
     *
     * Child disposal exceptions propagate immediately: remaining internal cleanup is not guaranteed
     * and repeated close does not retry it. The Settings root still attempts Session/defaults close
     * through its own finally blocks. No new best-effort cleanup/aggregation policy is implied.
     *
     * @throws Throwable if synchronous child/scope/channel/queue disposal throws, including
     * cancellation; asynchronous drain failures belong to the application failure path.
     */
    override fun close(): Unit
}
