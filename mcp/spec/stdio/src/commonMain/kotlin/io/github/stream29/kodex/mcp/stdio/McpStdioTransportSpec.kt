package io.github.stream29.kodex.mcp.stdio

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.github.stream29.kodex.utils.processclient.ProcessClientSpec

/** Boundary for starting a configured MCP server over its stdio process. */
public fun interface McpStdioTransportFactory {
    /**
     * Starts the configured server and returns a transport whose close operation
     * also terminates the owned process.
     *
     * @throws Throwable when process startup or transport construction fails.
     */
    public suspend fun open(
        processClient: ProcessClientSpec,
        configuration: McpServerConfiguration.Stdio,
    ): Transport
}
