package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

internal suspend fun CoroutineScope.KodexAgentState(
    client: OpenAiClient,
    storage: MutableKodexAgentStorage,
): KodexAgentStateContract =
    KodexAgentState(
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
    override val clients: StateFlow<Map<String, McpClient>> = MutableStateFlow(emptyMap())

    override suspend fun refresh() = Unit

    override fun close() = Unit
}
