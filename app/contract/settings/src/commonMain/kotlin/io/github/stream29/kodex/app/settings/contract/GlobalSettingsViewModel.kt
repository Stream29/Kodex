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

    /** Single Settings-host consumer for overlay intents; never broadcasts credentials or URLs. */
    public val effects: Flow<GlobalSettingsEffect>
    /** Shared application failure authority; render once and retain across popup reopening. */
    public val operationFailure: StateFlow<Boolean>
    /** Acknowledge the shared flag without rolling back commands or closing a child. */
    public fun dismissOperationFailure(): Unit

    /**
     * Idempotently reject new edits and close all child observations/transient operations.
     * Already admitted writes drain in application order before their dependent resources close.
     * Does not close backend stores or promise rollback of a remote command whose wait was cancelled.
     */
    override fun close(): Unit
}
