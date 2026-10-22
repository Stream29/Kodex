package io.github.stream29.kodex.mcp.streamablehttp

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.CoroutineScope

/**
 * Creates one MCP Streamable HTTP client owned by this scope.
 *
 * The returned value is an ordinary Ktor [HttpClient]. Completing or cancelling
 * the owning scope cancels the client and all of its in-flight requests.
 *
 * @throws IllegalArgumentException when the owning scope has no Job.
 */
public expect fun CoroutineScope.McpStreamableHttpClient(): HttpClient

/**
 * Creates a server-isolated client view with dynamic bearer authorization.
 *
 * The first callback may refresh an expiring token. A 401 requests one forced
 * refresh and retries only when a token is available. Each returned client has
 * its own interceptor, so credentials cannot be selected by URL or leak across
 * same-endpoint server declarations.
 */
public expect fun HttpClient.withMcpAuthorization(
    authorize: suspend (forceRefresh: Boolean) -> String?,
): HttpClient

/**
 * Creates an MCP transport using this client's engine and connection pool.
 * Configuration headers are applied to requests; authorization uses the isolated
 * view created by [withMcpAuthorization]. Closing the transport does not close
 * the borrowed client: its scope/connection owner releases that client.
 */
public expect fun HttpClient.openMcpStreamableHttpTransport(
    configuration: McpServerConfiguration.StreamableHttp,
): Transport
