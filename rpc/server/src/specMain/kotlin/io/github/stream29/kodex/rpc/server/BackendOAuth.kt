@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.cli.auth.BackendFileSystemAuthStore
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttempt
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.impl.McpManagerImpl
import io.github.stream29.kodex.mcp.impl.PreparedMcpOAuthLogin
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CoroutineScope

/**
 * Supplies listener-free prepared attempts to the manager's existing commit/cleanup path.
 *
 * [prepare] creates the login preparation for the supplied server configuration. The callback
 * remains private implementation state; the frontend owns its
 * loopback listener and submits the resulting callback URL through the backend OAuth service.
 */
public expect class BackendMcpOAuthBridge(
    prepare: suspend (McpServerConfiguration.StreamableHttp) -> PreparedMcpOAuthLogin,
) : McpOAuthLoginAttemptFactory {
    /**
     * Prepares one MCP attempt using the supplied callback.
     *
     * @throws Throwable if preparation fails; cancellation is propagated.
     */
    override suspend fun create(
        configuration: McpServerConfiguration.StreamableHttp,
    ): McpOAuthLoginAttempt
}

/**
 * Backend-owned OAuth attempt registry for OpenAI and MCP.
 *
 * The supplied scope parents this registry's work. [credentials], [openAi] and [mcp] are borrowed
 * collaborators and are not closed by this service. Attempt IDs are unique across both target
 * kinds for this service's lifetime and are not reused; only one attempt for an exact target may
 * be active at a time. A prepared attempt expires if no callback is accepted within ten minutes.
 *
 * Callback completion is bound to the original target, redirect URI and OAuth state. Callback
 * URLs are parsed as data and are never fetched; completion/cancellation never resolve a target
 * from current frontend state. Once a valid code is accepted, exchange and credential commit
 * remain backend-owned if the caller stops waiting. Public operations are accepted into this
 * service's owner scope, so cancelling a caller's wait does not cancel work already accepted.
 * Closing cancels owned work; internal backend shutdown uses the join operation to await cleanup.
 */
public expect class BackendOAuth : AutoCloseable {
    public constructor(
        scope: CoroutineScope,
        credentials: BackendFileSystemAuthStore,
        openAi: OpenAiLoginClient,
        mcp: McpManagerImpl,
    )
    /**
     * Prepares an attempt for [target] using the supplied loopback [redirectUri].
     * Existing OpenAI credentials remain until a replacement is committed. Preparation failures
     * are propagated; settings changes do not redirect the eventual credential write.
     *
     * @throws Throwable if redirect validation, target admission or provider preparation fails.
     */
    public suspend fun start(target: OAuthTarget, redirectUri: String): OAuthAuthorization

    /**
     * Submits the complete callback URL for the exact pending attempt and waits for exchange and
     * commit. Malformed or mismatched callbacks do not consume a valid attempt; a matching OAuth
     * error terminates it as failure. A valid code is accepted at most once.
     *
     * @throws IllegalStateException if the attempt is unknown, finished, not awaiting a callback,
     * or the callback reports an OAuth error.
     * @throws IllegalArgumentException if callback field, address or state validation fails.
     * @throws Throwable if callback URL parsing, provider exchange or credential commit fails.
     */
    public suspend fun complete(id: Long, callbackUrl: String): Unit

    /**
     * Cancels only [id]. Unknown or finished attempts are a no-op. This does not roll back MCP
     * preparation writes or committed credentials, and returning does not wait for all cleanup.
     *
     * @throws Throwable if the backend owner cannot accept the cancellation operation.
     */
    public suspend fun cancel(id: Long): Unit

    /**
     * Removes credentials only for [source] and invalidates that source's pending OpenAI login.
     * It does not change the selected source or revoke credentials remotely.
     *
     * @throws Throwable if credential removal fails.
     */
    public suspend fun removeAuthentication(source: KodexAuthSource): Unit

    /**
     * Logs out the named MCP server through its manager; the server configuration is retained and
     * the authenticated connection is closed by the manager's reconciliation. No active login
     * may remain, and logout does not revoke credentials remotely.
     *
     * @throws Throwable if the manager cannot complete logout.
     */
    public suspend fun logoutMcpServer(name: String): Unit

    /** Cancels the registry's owned work; this non-suspending operation does not join cleanup. */
    override fun close()
}
