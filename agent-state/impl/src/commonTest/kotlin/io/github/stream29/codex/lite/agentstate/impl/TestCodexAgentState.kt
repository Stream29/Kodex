package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.contract.AgentContextSettings
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.mcp.contract.McpTool
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

internal suspend fun CoroutineScope.CodexAgentState(
    client: OpenAiClient,
    storage: MutableCodexAgentStorage,
): CodexAgentStateContract =
    CodexAgentState(
        client = client,
        storage = storage,
        contextSettings = TestAgentContextSettings,
        mcpService = TestMcpService,
    )

internal val TestAgentContextSettings: StateFlow<AgentContextSettings> =
    MutableStateFlow(
        object : AgentContextSettings {
            override val codexHome: Path = Path(".")
            override val shell: Shell = Shell(ShellType.Sh, Path("sh"))
        },
    )

internal object TestMcpService : McpService {
    override val tools: StateFlow<List<McpTool>> = MutableStateFlow(emptyList())

    override suspend fun refresh() = Unit

    override fun close() = Unit
}
