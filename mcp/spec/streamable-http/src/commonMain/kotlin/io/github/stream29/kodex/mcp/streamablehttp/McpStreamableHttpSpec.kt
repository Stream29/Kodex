package io.github.stream29.kodex.mcp.streamablehttp

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.CoroutineScope

/** Factory boundary for a scope-owned MCP Streamable HTTP client. */
public interface McpStreamableHttpClientFactory {
    /** Creates a client cancelled together with [scope]. */
    public fun createClient(scope: CoroutineScope): HttpClient

    /** Creates a transport using the supplied, server-isolated client. */
    public fun openTransport(
        client: HttpClient,
        configuration: McpServerConfiguration.StreamableHttp,
    ): Transport
}
