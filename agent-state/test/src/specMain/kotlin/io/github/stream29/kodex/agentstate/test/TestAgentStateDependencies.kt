@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstate.test

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fixed application context for tests that do not exercise context discovery. */
public expect val TestAgentContextSettings: StateFlow<AgentContextSettings>

/** In-memory MCP service for tests that need no external MCP connections. */
public expect class TestMcpService(initialTools: List<McpTool> = emptyList()) : McpService {
    override val clients: MutableStateFlow<Map<String, McpClient>>
    override val authentication: StateFlow<Map<String, McpAuthenticationState>>
    public fun replaceTools(tools: List<McpTool>)
    override suspend fun refresh()
    override suspend fun invalidate(serverName: String)
    override fun close()
}
