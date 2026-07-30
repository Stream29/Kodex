package io.github.stream29.kodex.mcp.streamablehttp

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
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

/** Creates an MCP transport that reuses this client's engine and connection pool. */
public fun HttpClient.openMcpStreamableHttpTransport(
    configuration: McpServerConfiguration.StreamableHttp,
): Transport = StreamableHttpClientTransport(
    client = this,
    url = configuration.url,
) {
    headers {
        configuration.headers.forEach { (header, value) -> set(header, value) }
    }
}
