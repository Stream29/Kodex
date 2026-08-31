package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.SidebarSettings
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpOAuthSummary
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.time.Instant

/** Frontend-safe persistent fields rendered by Settings > Global. */
public data class GlobalSettingsState(
    public val settingsRevision: Long,
    public val codexHome: Path,
    public val authSource: KodexAuthSource,
    public val newLineKey: NewLineKey,
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

/** One exact Codex-home directory-picker child owned by Global Settings. */
public class GlobalCodexHomePicker(
    public val viewModel: DirectoryPickerViewModel,
)

/** Authentication projection that never contains request credentials. */
public sealed interface SettingsAuthenticationState {
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

    public data class Unavailable(
        public val reason: OpenAiAuthState.Unavailable,
    ) : SettingsAuthenticationState
}

/** Account-usage projection that keeps reset-attempt idempotency data private. */
public sealed interface SettingsAccountUsageState {
    public data object Unavailable : SettingsAccountUsageState

    public data class Loading(
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : SettingsAccountUsageState

    public data class Available(
        public val snapshot: CodexAccountUsageSnapshot,
    ) : SettingsAccountUsageState

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

/** Sanitized application-wide MCP server row. */
public data class McpServerSettingsState(
    public val serverName: String,
    public val transport: McpTransportKind,
    public val enabled: Boolean,
    public val authentication: McpAuthenticationState,
    public val status: McpServerSettingsStatus,
    public val headerNames: List<String> = emptyList(),
    public val environmentNames: List<String> = emptyList(),
    public val oauth: McpOAuthSummary? = null,
    public val streamableHttpUrl: String? = null,
    public val stdioCommand: String? = null,
    public val stdioArguments: List<String> = emptyList(),
    public val stdioWorkingDirectory: Path? = null,
) {
    init {
        require(serverName.isNotBlank()) { "An MCP Settings server name must not be blank." }
    }
}

/** MCP lifecycle data safe for frontend presentation. */
public sealed interface McpServerSettingsStatus {
    public data object Disabled : McpServerSettingsStatus
    public data class AuthenticationBlocked(
        public val state: McpAuthenticationState,
    ) : McpServerSettingsStatus

    public data object Connecting : McpServerSettingsStatus

    public data class Healthy(
        public val toolCount: Int,
    ) : McpServerSettingsStatus {
        init {
            require(toolCount >= 0) { "An MCP Settings tool count must not be negative." }
        }
    }

    public data class Failed(
        public val reason: McpClientFailureReason,
    ) : McpServerSettingsStatus

    public data object Closed : McpServerSettingsStatus
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

    public data class OpenMcpAuthorizationUrl(
        public val serverName: String,
        public val url: String,
    ) : GlobalSettingsEffect {
        init {
            require(serverName.isNotBlank()) { "An MCP server name must not be blank." }
            require(url.isNotBlank()) { "An MCP authorization URL must not be blank." }
        }
    }
}

/** Settings > Global state owner and command boundary. */
public interface GlobalSettingsViewModel : AutoCloseable {
    public val state: StateFlow<GlobalSettingsState>
    public val authentication: StateFlow<SettingsAuthenticationState>

    /** Account-isolated projection without authentication or idempotency credentials. */
    public val accountUsage: StateFlow<SettingsAccountUsageState>

    public val mcpServers: StateFlow<List<McpServerSettingsState>>
    public val mcpImportPreview: StateFlow<McpImportPreview?>
    public val hooks: StateFlow<List<HookManagedState>>
    public val usageReset: StateFlow<UsageResetState>
    public val codexHomePicker: StateFlow<GlobalCodexHomePicker?>
    public val effects: Flow<GlobalSettingsEffect>

    public fun requestCodexHome(): Unit

    /** Applies a directory only while [expected] is the current owned child. */
    public fun selectCodexHome(
        expected: GlobalCodexHomePicker,
        codexHome: Path,
    ): Boolean

    /** Closes [expected] only while it is the current owned child. */
    public fun dismissCodexHomePicker(expected: GlobalCodexHomePicker): Boolean

    public fun updateNewLineKey(newLineKey: NewLineKey): Unit
    public fun updateAuthSource(authSource: KodexAuthSource): Unit
    public fun updateSessionTitleEnabled(enabled: Boolean): Unit
    public fun updateSessionTitleModel(model: OpenAiModelId): Unit
    public fun updateSessionTitleReasoningEffort(reasoningEffort: ReasoningEffort): Unit
    public fun updateLeftSidebarWidth(columns: Int): Unit
    public fun updateRightSidebarWidth(columns: Int): Unit

    public fun requestLogin(): Unit
    public fun refreshUsage(): Unit
    public fun requestUsageReset(): Unit
    public fun selectUsageReset(option: UsageResetOption): Unit
    public fun returnToUsageResetChoices(): Unit
    public fun confirmUsageReset(): Unit
    public fun retryUsageReset(): Unit
    public fun dismissUsageReset(): Unit

    public fun reconnectMcpServer(serverName: String): Unit
    public fun addMcpServer(draft: McpServerDraft): Unit
    public fun editMcpServer(existingServerName: String, draft: McpServerDraft): Unit
    public fun deleteMcpServer(serverName: String): Unit
    public fun setMcpServerEnabled(serverName: String, enabled: Boolean): Unit
    public fun loginMcpServer(serverName: String): Unit
    public fun cancelMcpServerLogin(serverName: String): Unit
    public fun logoutMcpServer(serverName: String): Unit
    public fun previewCodexMcpImport(filter: String = ""): Unit
    public fun applyCodexMcpImport(
        previewId: Long,
        decisions: Map<String, McpImportDecision>,
    ): Unit
    public fun dismissCodexMcpImport(): Unit

    public fun addHook(draft: HookDraft): Unit
    public fun editHook(name: String, draft: HookDraft): Unit
    public fun deleteHook(name: String): Unit
    public fun hookEditorDraft(name: String): HookDraft?

    override fun close(): Unit
}
