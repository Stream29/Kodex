package io.github.stream29.kodex.mcp.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Transport kind safe to render without exposing a configured endpoint. */
public enum class McpTransportKind {
    StreamableHttp,
    Stdio,
}

/** Persisted and transient authentication state safe for presentation. */
public sealed interface McpAuthenticationState {
    public data object NotConfigured : McpAuthenticationState
    public data object LoginRequired : McpAuthenticationState
    public data object ReauthorizationRequired : McpAuthenticationState
    public data object Authorizing : McpAuthenticationState
    public data object Authorized : McpAuthenticationState
    public data object Refreshing : McpAuthenticationState

    public data class Failed(
        public val message: String,
    ) : McpAuthenticationState {
        init {
            require(message.isNotBlank()) { "An MCP authentication failure must not be blank." }
        }
    }
}

/** Complete sanitized state for one Kodex-owned MCP server. */
public data class McpManagedServerState(
    public val serverName: String,
    public val transport: McpTransportKind,
    public val enabled: Boolean,
    public val authentication: McpAuthenticationState,
    public val connection: McpClientState?,
    public val toolCount: Int,
    public val headerNames: List<String> = emptyList(),
    public val environmentNames: List<String> = emptyList(),
    public val oauth: McpOAuthSummary? = null,
    public val streamableHttpUrl: String? = null,
    public val stdioCommand: String? = null,
    public val stdioArguments: List<String> = emptyList(),
    public val stdioWorkingDirectory: Path? = null,
) {
    init {
        require(serverName.isNotBlank()) { "An MCP server name must not be blank." }
        require(toolCount >= 0) { "An MCP tool count must not be negative." }
        require(headerNames == headerNames.distinct().sorted()) {
            "MCP header names must be unique and sorted."
        }
        require(environmentNames == environmentNames.distinct().sorted()) {
            "MCP environment names must be unique and sorted."
        }
    }
}

/** Credential-free OAuth identity used by Settings editors and status rows. */
public data class McpOAuthSummary(
    public val clientId: String,
    public val hasClientSecret: Boolean,
    public val redirectUri: String,
    public val authorizationEndpoint: String?,
    public val tokenEndpoint: String?,
    public val resource: String?,
    public val scopes: List<String>,
)

/** Secret input used only by a manager command and never published in state. */
public sealed interface McpSecretDraft {
    /** Retains the existing value while editing. Invalid for a new key. */
    public data object Keep : McpSecretDraft

    /** Replaces the value with the supplied secret. */
    public data class Replace(
        public val value: String,
    ) : McpSecretDraft
}

/** Editable Streamable HTTP server input. */
public data class McpStreamableHttpDraft(
    public val url: String,
    public val headers: Map<String, McpSecretDraft> = emptyMap(),
    public val oauth: McpOAuthDraft? = null,
)

/** Editable stdio server input. */
public data class McpStdioDraft(
    public val command: String,
    public val args: List<String> = emptyList(),
    public val environment: Map<String, McpSecretDraft> = emptyMap(),
    public val workingDirectory: Path = Path("."),
)

/** OAuth client input; [clientSecret] follows preserve-or-replace edit semantics. */
public data class McpOAuthDraft(
    public val clientId: String,
    public val clientSecret: McpSecretDraft? = null,
    public val redirectUri: String = DefaultMcpOAuthRedirectUri,
    public val authorizationEndpoint: String? = null,
    public val tokenEndpoint: String? = null,
    public val resource: String? = null,
    public val scopes: List<String> = emptyList(),
)

/** Validated manager input for adding or editing one uniquely named server. */
public sealed interface McpServerDraft {
    public val serverName: String
    public val enabled: Boolean

    public data class StreamableHttp(
        override val serverName: String,
        override val enabled: Boolean = true,
        public val configuration: McpStreamableHttpDraft,
    ) : McpServerDraft

    public data class Stdio(
        override val serverName: String,
        override val enabled: Boolean = true,
        public val configuration: McpStdioDraft,
    ) : McpServerDraft
}

/** Classification of one Codex MCP declaration in an import preview. */
public enum class McpImportItemKind {
    New,
    Conflict,
    Unsupported,
}

/** One credential-free Codex import preview item. */
public data class McpImportItem(
    public val serverName: String,
    public val transport: McpTransportKind?,
    public val kind: McpImportItemKind,
    public val enabled: Boolean?,
    public val selectable: Boolean,
    public val detail: String? = null,
)

/** Immutable preview token and its filtered, credential-free entries. */
public data class McpImportPreview(
    public val id: Long,
    public val filter: String,
    public val items: List<McpImportItem>,
) {
    init {
        require(id > 0) { "An MCP import preview id must be positive." }
    }
}

/** Explicit action for one preview item during the atomic import commit. */
public enum class McpImportDecision {
    Skip,
    Import,
    Replace,
}

/** Browser authorization attempt owned by its caller until completion or cancellation. */
public interface McpOAuthLoginAttempt : AutoCloseable {
    public val authorizationUrl: String
    public suspend fun awaitInitialized(): McpOAuthConfiguration.Initialized
    override fun close(): Unit
}

/** Atomic persistence port shared by the manager and runtime token refresher. */
public interface McpConfigurationStore {
    public val configurations: StateFlow<Map<String, McpServerConfiguration>>

    public suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration>
}

/** One server declaration read by an explicit Codex import operation. */
public sealed interface McpCodexImportCandidate {
    public val serverName: String
    public val transport: McpTransportKind?

    /** A declaration Kodex can persist without silently dropping behavior. */
    public data class Supported(
        override val serverName: String,
        public val configuration: McpServerConfiguration,
    ) : McpCodexImportCandidate {
        override val transport: McpTransportKind =
            when (configuration) {
                is McpServerConfiguration.StreamableHttp -> McpTransportKind.StreamableHttp
                is McpServerConfiguration.Stdio -> McpTransportKind.Stdio
            }
    }

    /** A credential-free explanation of a declaration Kodex cannot import. */
    public data class Unsupported(
        override val serverName: String,
        override val transport: McpTransportKind?,
        public val detail: String,
    ) : McpCodexImportCandidate {
        init {
            require(detail.isNotBlank()) {
                "An unsupported Codex MCP import detail must not be blank."
            }
        }
    }
}

/** Explicit, one-shot Codex MCP import source. */
public fun interface McpCodexImportSource {
    public suspend fun read(): List<McpCodexImportCandidate>
}

/** Creates one browser OAuth attempt from an uninitialized server declaration. */
public fun interface McpOAuthLoginAttemptFactory {
    public suspend fun create(
        configuration: McpServerConfiguration.StreamableHttp,
    ): McpOAuthLoginAttempt
}

/** Refreshes one initialized OAuth token set without changing client identity. */
public fun interface McpOAuthTokenRefresher {
    public suspend fun refresh(
        configuration: McpOAuthConfiguration.Initialized,
    ): McpOAuthConfiguration.Initialized
}

/** One-shot manager effect that must be handled by the frontend host. */
public sealed interface McpManagerEffect {
    public data class OpenAuthorizationUrl(
        public val serverName: String,
        public val url: String,
    ) : McpManagerEffect
}

/**
 * Application-wide MCP command and sanitized-state authority.
 *
 * Draft commands validate first and persist one atomic settings transform.
 * Server rename is represented by [edit] with a different draft name.
 */
public interface McpManager : AutoCloseable {
    public val servers: StateFlow<List<McpManagedServerState>>
    public val effects: Flow<McpManagerEffect>

    public suspend fun add(draft: McpServerDraft): Unit
    public suspend fun edit(existingServerName: String, draft: McpServerDraft): Unit
    public suspend fun delete(serverName: String): Unit
    public suspend fun setEnabled(serverName: String, enabled: Boolean): Unit
    public suspend fun login(serverName: String): Unit
    public suspend fun cancelLogin(serverName: String): Unit
    public suspend fun logout(serverName: String): Unit
    public suspend fun reconnect(serverName: String): Unit

    public suspend fun previewCodexImport(filter: String = ""): McpImportPreview
    public suspend fun applyCodexImport(
        previewId: Long,
        decisions: Map<String, McpImportDecision>,
    ): Unit

    override fun close(): Unit
}
