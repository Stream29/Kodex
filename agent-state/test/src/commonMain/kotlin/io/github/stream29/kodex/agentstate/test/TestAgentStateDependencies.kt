package io.github.stream29.kodex.agentstate.test

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Fixed application context for tests that do not exercise context discovery. */
public val TestAgentContextSettings: StateFlow<AgentContextSettings> =
    MutableStateFlow(
        object : AgentContextSettings {
            override val agentsHome: Path = Path(".")
            override val kodexHome: Path = Path(".")
            override val codexHome: Path = Path(".")
            override val shell: Shell = Shell(ShellType.Sh, Path("sh"))
            override val sources: AgentContextSourceSettings = AgentContextSourceSettings()
        },
    )

/** In-memory MCP service for tests that need no external MCP connections. */
public class TestMcpService(
    initialTools: List<McpTool> = emptyList(),
) : McpService {
    override val clients: MutableStateFlow<Map<String, McpClient>> =
        MutableStateFlow(initialTools.toTestMcpClients())
    override val authentication: StateFlow<Map<String, McpAuthenticationState>> =
        MutableStateFlow(emptyMap())

    public fun replaceTools(tools: List<McpTool>) {
        clients.value = tools.toTestMcpClients()
    }

    override suspend fun refresh(): Unit = Unit

    override suspend fun invalidate(serverName: String): Unit = Unit

    override fun close(): Unit = Unit
}

private class TestMcpClient(
    override val serverName: String,
    private val tools: List<McpTool>,
) : McpClient {
    override val state: StateFlow<McpClientState> = MutableStateFlow(McpClientState.Healthy)

    override fun listTools(): List<McpTool> = tools

    override suspend fun reconnect(): Unit = Unit
}

private fun List<McpTool>.toTestMcpClients(): Map<String, McpClient> =
    groupBy(McpTool::serverName)
        .mapValues { (serverName, tools) ->
            TestMcpClient(serverName = serverName, tools = tools.toList())
        }
