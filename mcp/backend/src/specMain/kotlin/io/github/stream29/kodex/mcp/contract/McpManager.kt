package io.github.stream29.kodex.mcp.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Browser authorization attempt owned by its caller until completion or cancellation. */
public interface McpOAuthLoginAttempt : AutoCloseable {
    /** Discovery and registration results that must be persisted before opening the browser. */
    public val preparedConfiguration: McpOAuthConfiguration.Uninitialized
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
