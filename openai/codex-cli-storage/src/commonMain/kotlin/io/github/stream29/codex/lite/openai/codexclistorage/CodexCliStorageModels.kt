package io.github.stream29.codex.lite.openai.codexclistorage

import io.github.stream29.codex.lite.openai.ModelInfo
import io.github.stream29.codex.lite.openai.ReasoningEffort
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

/** One native Codex MCP server declaration. */
@Serializable
public data class CodexCliMcpServer(
    /** Nullable because stdio MCP servers do not have a URL. */
    public val url: String? = null,
    @SerialName("http_headers")
    public val headers: Map<String, String> = emptyMap(),
    /** Nullable because a server is enabled unless the layer says otherwise. */
    public val enabled: Boolean? = null,
)

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
 * @property authMode Nullable because Codex CLI may omit the auth mode.
 * @property tokens Nullable because only token-backed modes store this object.
 */
@Serializable
public data class CodexAuthJson(
    @SerialName("auth_mode")
    public val authMode: CodexAuthMode? = null,
    public val tokens: CodexAuthTokens? = null,
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

/**
 * @property idToken Raw JWT required by Codex CLI's token data.
 * @property accessToken Required bearer JWT.
 * @property refreshToken Required token used by Codex CLI to renew the session.
 * @property accountId Nullable because Codex CLI may omit the account id;
 * `null` means no account id should be propagated.
 */
@Serializable
public data class CodexAuthTokens(
    @SerialName("id_token")
    public val idToken: String,
    @SerialName("access_token")
    public val accessToken: String,
    @SerialName("refresh_token")
    public val refreshToken: String,
    @SerialName("account_id")
    public val accountId: String? = null,
)
