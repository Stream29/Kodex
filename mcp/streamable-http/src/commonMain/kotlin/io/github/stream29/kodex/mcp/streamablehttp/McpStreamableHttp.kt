package io.github.stream29.kodex.mcp.streamablehttp

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Creates one MCP Streamable HTTP client owned by this scope.
 *
 * The returned value is an ordinary Ktor [HttpClient]. Completing or cancelling
 * the owning scope cancels the client and all of its in-flight requests.
 */
public fun CoroutineScope.McpStreamableHttpClient(): HttpClient {
    val ownerJob = requireNotNull(coroutineContext[Job]) {
        "McpStreamableHttpClient requires an owning CoroutineScope with a Job."
    }
    return HttpClient {
        install(SSE)
    }.also { client ->
        ownerJob.invokeOnCompletion {
            client.cancel()
        }
    }
}

/**
 * Creates a server-isolated client view with dynamic bearer authorization.
 *
 * The first callback may refresh an expiring token. A 401 requests one forced
 * refresh and retries only when a token is available. Each returned client has
 * its own interceptor, so credentials cannot be selected by URL or leak across
 * same-endpoint server declarations.
 */
public fun HttpClient.withMcpAuthorization(
    authorize: suspend (forceRefresh: Boolean) -> String?,
): HttpClient =
    config {}.also { client ->
        client.plugin(HttpSend).intercept { request ->
            authorize(false)?.let { token ->
                request.headers.remove(HttpHeaders.Authorization)
                request.header(HttpHeaders.Authorization, "Bearer $token")
            }
            val response = execute(request)
            if (response.response.status != HttpStatusCode.Unauthorized) {
                return@intercept response
            }
            val refreshed = authorize(true) ?: return@intercept response
            response.response.bodyAsChannel().cancel(
                CancellationException("Retrying MCP request after authorization refresh."),
            )
            request.headers.remove(HttpHeaders.Authorization)
            request.header(HttpHeaders.Authorization, "Bearer $refreshed")
            execute(request)
        }
    }

/** Creates an MCP transport that reuses this client's engine and connection pool. */
public fun HttpClient.openMcpStreamableHttpTransport(
    configuration: McpServerConfiguration.StreamableHttp,
): Transport = StreamableHttpClientTransport(
    client = this,
    url = configuration.url,
) {
    headers {
        configuration.headers.forEach { (header, value) -> set(header, value.value) }
    }
}
