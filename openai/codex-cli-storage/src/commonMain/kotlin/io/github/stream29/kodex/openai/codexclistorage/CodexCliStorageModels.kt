package io.github.stream29.kodex.openai.codexclistorage

import dev.eav.tomlkt.TomlContentPolymorphicSerializer
import dev.eav.tomlkt.TomlElement
import dev.eav.tomlkt.asTomlTable
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** One decoded Codex CLI TOML configuration layer. */
@Serializable
public data class CodexCliConfig(
    /** Nullable because a configuration layer may inherit the model. */
    public val model: String? = null,
    /** Nullable because a configuration layer may inherit reasoning effort. */
    @SerialName("model_reasoning_effort")
    public val reasoningEffort: ReasoningEffort? = null,
    /** Nullable because a configuration layer may inherit its service tier. */
    @SerialName("service_tier")
    public val serviceTier: String? = null,
    /** Nullable because a configuration layer may omit all TUI settings. */
    public val tui: CodexCliTuiConfig? = null,
    @SerialName("mcp_servers")
    public val mcpServers: Map<String, CodexCliMcpServer> = emptyMap(),
)

/** Native Codex TUI configuration. */
@Serializable
public data class CodexCliTuiConfig(
    /** Defaults to an empty keymap when `[tui.keymap]` is absent. */
    public val keymap: CodexCliKeymap = CodexCliKeymap(),
)

/** Native Codex TUI key bindings. */
@Serializable
public data class CodexCliKeymap(
    /** Defaults to an empty composer map when `[tui.keymap.composer]` is absent. */
    public val composer: CodexCliComposerKeymap = CodexCliComposerKeymap(),
    /** Defaults to an empty global map when `[tui.keymap.global]` is absent. */
    public val global: CodexCliGlobalKeymap = CodexCliGlobalKeymap(),
    /** Defaults to an empty editor map when `[tui.keymap.editor]` is absent. */
    public val editor: CodexCliEditorKeymap = CodexCliEditorKeymap(),
)

/** Native Codex composer key bindings. */
@Serializable
public data class CodexCliComposerKeymap(
    /** Nullable because the composer submit binding can use its default. */
    public val submit: String? = null,
)

/** Native Codex global key bindings. */
@Serializable
public data class CodexCliGlobalKeymap(
    /** Nullable because the global submit binding can use its default. */
    public val submit: String? = null,
)

/** Native Codex editor key bindings. */
@Serializable
public data class CodexCliEditorKeymap(
    /** Nullable because the editor newline binding can use its default. */
    @SerialName("insert_newline")
    public val insertNewline: String? = null,
)

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
 * Read-only snapshot written by Codex CLI to `models_cache.json`.
 *
 * Kotlin code must not write this file: it is a shared cache whose complete
 * model entries are owned by the installed Codex CLI.
 *
 * @property etag Nullable because a cache entry may have been written without
 * an HTTP entity tag; `null` means no entity tag is available for comparison.
 * @property clientVersion Nullable because older or partial Codex CLI cache
 * files may omit it; `null` means no cached client version is available.
 */
@Serializable
public data class CodexModelsCache(
    @SerialName("fetched_at")
    public val fetchedAt: Instant,
    public val etag: String? = null,
    @SerialName("client_version")
    public val clientVersion: String? = null,
    public val models: List<ModelInfo>,
)

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
