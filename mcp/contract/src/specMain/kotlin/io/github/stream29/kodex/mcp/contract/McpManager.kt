package io.github.stream29.kodex.mcp.contract

import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Transport kind safe to render without exposing a configured endpoint. */
@Serializable
public enum class McpTransportKind {
    StreamableHttp,
    Stdio,
}

/** Persisted and transient authentication state safe for presentation. */
@Serializable
public sealed interface McpAuthenticationState {
    @Serializable
    public data object NotConfigured : McpAuthenticationState
    @Serializable
    public data object LoginRequired : McpAuthenticationState
    @Serializable
    public data object ReauthorizationRequired : McpAuthenticationState
    @Serializable
    public data object Authorizing : McpAuthenticationState
    @Serializable
    public data object Authorized : McpAuthenticationState
    @Serializable
    public data object Refreshing : McpAuthenticationState

    @Serializable
    public data class Failed(
        public val message: String,
    ) : McpAuthenticationState {
        init {
            require(message.isNotBlank()) { "An MCP authentication failure must not be blank." }
        }
    }
}

/** Complete sanitized state for one Kodex-owned MCP server. */
@Serializable
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
    @Serializable(with = PathAsStringSerializer::class)
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
@Serializable
public data class McpOAuthSummary(
    public val clientId: String?,
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
    public val clientId: String? = null,
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

/** One server declaration read by an explicit Codex import operation. */
@Serializable
public sealed interface McpCodexImportCandidate {
    public val serverName: String
    public val transport: McpTransportKind?

    /** A declaration Kodex can persist without silently dropping behavior. */
    @Serializable
    public data class Supported(
        override val serverName: String,
        public val configuration: McpServerConfiguration,
    ) : McpCodexImportCandidate {
        @Transient
        override val transport: McpTransportKind =
            when (configuration) {
                is McpServerConfiguration.StreamableHttp -> McpTransportKind.StreamableHttp
                is McpServerConfiguration.Stdio -> McpTransportKind.Stdio
            }
    }

    /** A credential-free explanation of a declaration Kodex cannot import. */
    @Serializable
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
