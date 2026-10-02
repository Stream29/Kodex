package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.SidebarSettings
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Frontend-safe persistent fields rendered by Settings > Global. */
public data class GlobalSettingsState(
    public val settingsRevision: Long,
    public val authSource: KodexAuthSource,
    public val newLineKey: NewLineKey,
    public val contextSources: AgentContextSourceSettings,
    public val sessionTitle: SessionTitleSettings,
    public val sidebars: SidebarSettings,
    public val effectiveSessionTitleModel: OpenAiModelId,
    public val modelOptions: List<OpenAiModelId>,
) {
    init {
        require(settingsRevision >= 0) { "A global Settings revision must not be negative." }
        require(modelOptions.distinct().size == modelOptions.size) {
            "Global Settings model options must be unique."
        }
        require(effectiveSessionTitleModel in modelOptions) {
            "The effective Session-title model must be selectable."
        }
    }
}

/** Built-in context roots that can be disabled but not edited or removed. */
public enum class BuiltInContextSource {
    AgentsHome,
    KodexHome,
    CodexHome,
    GitRoot,
    WorkingDirectory,
}

/** Authentication projection that never contains request credentials. */
@Serializable
public sealed interface SettingsAuthenticationState {
    @Serializable
    public data class Authenticated(
        public val accountId: String? = null,
        public val planType: OpenAiSubscriptionPlan? = null,
        public val email: String? = null,
    ) : SettingsAuthenticationState {
        init {
            require(accountId == null || accountId.isNotBlank()) {
                "A Settings account id must be null or non-blank."
            }
            require(email == null || email.isNotBlank()) {
                "A Settings account email must be null or non-blank."
            }
        }
    }

    @Serializable
    public data class Unavailable(
        public val reason: OpenAiAuthState.Unavailable,
    ) : SettingsAuthenticationState
}

/** One account-management command owned by Settings > OpenAI. */
public enum class SettingsAuthenticationOperation {
    Logout,
}

/** Frontend-safe lifecycle of the current account-management command. */
public sealed interface SettingsAuthenticationOperationState {
    public data object Idle : SettingsAuthenticationOperationState
    public data object SigningOut : SettingsAuthenticationOperationState

    public data class Failed(
        public val operation: SettingsAuthenticationOperation,
    ) : SettingsAuthenticationOperationState
}

/** Account-usage projection that keeps reset-attempt idempotency data private. */
@Serializable
public sealed interface SettingsAccountUsageState {
    @Serializable
    public data object Unavailable : SettingsAccountUsageState

    /** @property previous Null when no same-account fallback snapshot is available. */
    @Serializable
    public data class Loading(
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : SettingsAccountUsageState

    @Serializable
    public data class Available(
        public val snapshot: CodexAccountUsageSnapshot,
    ) : SettingsAccountUsageState

    /** @property previous Null when no same-account fallback snapshot is available. */
    @Serializable
    public data class Failed(
        public val message: String,
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : SettingsAccountUsageState {
        init {
            require(message.isNotBlank()) {
                "A Settings account-usage failure message must not be blank."
            }
        }
    }

    @Serializable
    public data class Redeeming(
        public val snapshot: CodexAccountUsageSnapshot,
    ) : SettingsAccountUsageState
}

/** Returns the same-account snapshot retained by a Settings usage state. */
public fun SettingsAccountUsageState.snapshotOrNull(): CodexAccountUsageSnapshot? =
    when (this) {
        is SettingsAccountUsageState.Available -> snapshot
        is SettingsAccountUsageState.Failed -> previous
        is SettingsAccountUsageState.Loading -> previous
        is SettingsAccountUsageState.Redeeming -> snapshot
        SettingsAccountUsageState.Unavailable -> null
    }

/** One reset credit choice without its private idempotency attempt. */
public data class UsageResetOption(
    public val creditId: String?,
    public val title: String,
    public val description: String,
    public val expiresAt: Instant?,
) {
    init {
        require(creditId == null || creditId.isNotBlank()) {
            "A usage-reset credit id must be null or non-blank."
        }
        require(title.isNotBlank()) { "A usage-reset title must not be blank." }
        require(description.isNotBlank()) { "A usage-reset description must not be blank." }
    }
}

/** Reset choices derived from one account-isolated usage snapshot. */
public data class UsageResetRequest(
    public val availableCount: Long,
    public val options: List<UsageResetOption>,
) {
    init {
        require(availableCount > 0) { "A usage-reset request must have an available reset." }
        require(options.isNotEmpty()) { "A usage-reset request must have a selectable option." }
        require(availableCount >= options.size) {
            "A usage-reset request cannot expose more options than available resets."
        }
    }
}

/** Complete confirmation and consumption workflow for one usage reset. */
public sealed interface UsageResetState {
    public data object Hidden : UsageResetState

    public data class Choosing(
        public val request: UsageResetRequest,
    ) : UsageResetState

    public data class Preparing(
        public val option: UsageResetOption,
    ) : UsageResetState

    public data class Confirming(
        public val option: UsageResetOption,
    ) : UsageResetState

    public data class Consuming(
        public val option: UsageResetOption,
    ) : UsageResetState

    public data class ConsumeFailed(
        public val option: UsageResetOption,
    ) : UsageResetState

    public data object PreparationFailed : UsageResetState

    public data class Completed(
        public val outcome: CodexRateLimitResetOutcome,
        public val selectedCredit: Boolean,
    ) : UsageResetState
}

/** One-shot application overlay requested by the Global Settings child. */
public sealed interface GlobalSettingsEffect {
    public data object OpenLogin : GlobalSettingsEffect
}

/** Settings > Global state owner and command boundary. */
public interface GlobalSettingsViewModel : AutoCloseable {
    public val state: StateFlow<GlobalSettingsState>
    public val authentication: StateFlow<SettingsAuthenticationState>
    public val authenticationOperation: StateFlow<SettingsAuthenticationOperationState>

    /** Account-isolated projection without authentication or idempotency credentials. */
    public val accountUsage: StateFlow<SettingsAccountUsageState>

    /** Stable MCP management child; owns its dialogs/drafts, not the shared backend clients. */
    public val mcpSettings: McpSettingsViewModel
    /** Stable local notification configuration child; does not execute Hook commands. */
    public val hookSettings: HookSettingsViewModel
    public val usageReset: StateFlow<UsageResetState>
    public val effects: Flow<GlobalSettingsEffect>
    /** Application-owned failure flag also survives closing and reopening this popup. */
    public val operationFailure: StateFlow<Boolean>
    public fun dismissOperationFailure(): Unit

    public fun setBuiltInContextSourceEnabled(
        source: BuiltInContextSource,
        enabled: Boolean,
    ): Unit

    /** Returns a validation error, or null after queuing the new source. */
    public fun addCustomContextSource(path: String): String?

    public fun setCustomContextSourceEnabled(path: String, enabled: Boolean): Unit
    public fun removeCustomContextSource(path: String): Unit

    public fun updateNewLineKey(newLineKey: NewLineKey): Unit
    public fun updateAuthSource(authSource: KodexAuthSource): Unit
    public fun updateSessionTitleEnabled(enabled: Boolean): Unit
    public fun updateSessionTitleModel(model: OpenAiModelId): Unit
    public fun updateSessionTitleReasoningEffort(reasoningEffort: ReasoningEffort): Unit
    public fun updateLeftSidebarWidth(columns: Int): Unit
    public fun updateRightSidebarWidth(columns: Int): Unit

    public fun requestLogin(): Unit
    public fun removeAuthentication(): Unit
    public fun dismissAuthenticationOperationFailure(): Unit
    public fun refreshUsage(): Unit
    public fun requestUsageReset(): Unit
    public fun selectUsageReset(option: UsageResetOption): Unit
    public fun returnToUsageResetChoices(): Unit
    public fun confirmUsageReset(): Unit
    public fun retryUsageReset(): Unit
    public fun dismissUsageReset(): Unit

    override fun close(): Unit
}
