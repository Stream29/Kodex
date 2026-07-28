package io.github.stream29.codex.lite.agentstate.test

import io.github.stream29.codex.lite.agentcontext.contract.AgentContextSettings
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.mcp.contract.McpTool
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Fixed application context for tests that do not exercise context discovery. */
public val TestAgentContextSettings: StateFlow<AgentContextSettings> =
    MutableStateFlow(
        object : AgentContextSettings {
            override val codexHome: Path = Path(".")
            override val shell: Shell = Shell(ShellType.Sh, Path("sh"))
        },
    )

/** In-memory MCP service for tests that need no external MCP connections. */
public class TestMcpService(
    initialTools: List<McpTool> = emptyList(),
) : McpService {
    override val tools: MutableStateFlow<List<McpTool>> = MutableStateFlow(initialTools.toList())

    override suspend fun refresh(): Unit = Unit

    override fun close(): Unit = Unit
}
