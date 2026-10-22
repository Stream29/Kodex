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

public actual fun CoroutineScope.McpStreamableHttpClient(): HttpClient {
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

public actual fun HttpClient.withMcpAuthorization(
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

public actual fun HttpClient.openMcpStreamableHttpTransport(
    configuration: McpServerConfiguration.StreamableHttp,
): Transport = StreamableHttpClientTransport(
    client = this,
    url = configuration.url,
) {
    headers {
        configuration.headers.forEach { (header, value) -> set(header, value.value) }
    }
}
