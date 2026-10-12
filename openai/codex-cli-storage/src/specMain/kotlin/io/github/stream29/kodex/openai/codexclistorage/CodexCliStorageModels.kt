package io.github.stream29.kodex.openai.codexclistorage

import dev.eav.tomlkt.TomlContentPolymorphicSerializer
import dev.eav.tomlkt.TomlElement
import dev.eav.tomlkt.asTomlTable
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** One native Codex MCP server declaration, decoded from its untagged TOML transport shape. */
@Serializable(with = CodexCliMcpServerSerializer::class)
public sealed interface CodexCliMcpServer {
    public val enabled: Boolean

    /** A server reached through MCP Streamable HTTP. */
    @Serializable
    public data class StreamableHttp(
        public val url: String,
        @SerialName("http_headers")
        public val headers: Map<String, String> = emptyMap(),
        public val auth: CodexCliMcpAuth? = null,
        public val scopes: List<String>? = null,
        public val oauth: CodexCliMcpOAuth? = null,
        @SerialName("oauth_resource")
        public val oauthResource: String? = null,
        override val enabled: Boolean = true,
    ) : CodexCliMcpServer

    /** A local MCP server launched as a direct child process. */
    @Serializable
    public data class Stdio(
        public val command: String,
        public val args: List<String> = emptyList(),
        public val env: Map<String, String> = emptyMap(),
        public val cwd: String = ".",
        override val enabled: Boolean = true,
    ) : CodexCliMcpServer
}

/** Codex HTTP MCP fallback authentication modes relevant to import support. */
@Serializable
public enum class CodexCliMcpAuth {
    @SerialName("oauth")
    OAuth,

    @SerialName("chatgpt")
    ChatGpt,
}

/** Codex OAuth client declaration; missing ids require dynamic registration. */
@Serializable
public data class CodexCliMcpOAuth(
    @SerialName("client_id")
    public val clientId: String? = null,
)

/** Transport hint safe to show for an unsupported Codex MCP declaration. */
public enum class CodexCliMcpTransportKind {
    StreamableHttp,
    Stdio,
}

/** One declaration classified specifically for an explicit import preview. */
public sealed interface CodexCliMcpImportCandidate {
    public val serverName: String
    public val transport: CodexCliMcpTransportKind?

    public data class Supported(
        override val serverName: String,
        public val configuration: CodexCliMcpServer,
    ) : CodexCliMcpImportCandidate {
        override val transport: CodexCliMcpTransportKind =
            when (configuration) {
                is CodexCliMcpServer.StreamableHttp ->
                    CodexCliMcpTransportKind.StreamableHttp

                is CodexCliMcpServer.Stdio -> CodexCliMcpTransportKind.Stdio
            }
    }

    public data class Unsupported(
        override val serverName: String,
        override val transport: CodexCliMcpTransportKind?,
        /** Contains field names or a generic decode failure, never field values. */
        public val detail: String,
    ) : CodexCliMcpImportCandidate
}

/** Selects Codex's untagged MCP transport from its required transport field. */
public object CodexCliMcpServerSerializer :
    TomlContentPolymorphicSerializer<CodexCliMcpServer>(CodexCliMcpServer::class) {
    override fun selectDeserializer(element: TomlElement): DeserializationStrategy<CodexCliMcpServer> =
        if ("command" in element.asTomlTable()) {
            CodexCliMcpServer.Stdio.serializer()
        } else {
            CodexCliMcpServer.StreamableHttp.serializer()
        }
}

/**
 * @property openAiApiKey Nullable because subscription authentication does not
 * store an API key; `null` means this file has no API-key credential.
 * @property authMode Nullable because Codex CLI may omit the auth mode.
 * @property tokens Nullable because only token-backed modes store this object.
 * @property lastRefresh Nullable because older or externally managed Codex
 * auth files may omit refresh metadata; `null` means no refresh time was
 * persisted.
 */
@Serializable
public data class CodexAuthJson(
    @SerialName("OPENAI_API_KEY")
    public val openAiApiKey: String? = null,
    @SerialName("auth_mode")
    public val authMode: CodexAuthMode? = null,
    public val tokens: OpenAiSubscriptionTokens? = null,
    @SerialName("last_refresh")
    public val lastRefresh: Instant? = null,
)

/** All Rust `AuthMode` wire variants accepted by Codex CLI. */
@Serializable
public enum class CodexAuthMode {
    @SerialName("apikey")
    ApiKey,

    @SerialName("chatgpt")
    Chatgpt,

    @SerialName("chatgptAuthTokens")
    ChatgptAuthTokens,

    @SerialName("headers")
    Headers,

    @SerialName("agentIdentity")
    AgentIdentity,

    @SerialName("personalAccessToken")
    PersonalAccessToken,

    @SerialName("bedrockApiKey")
    BedrockApiKey,
}
