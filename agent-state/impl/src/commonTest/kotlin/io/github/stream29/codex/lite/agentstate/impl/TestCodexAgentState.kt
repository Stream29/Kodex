package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.io.files.Path

internal suspend fun CodexAgentState(
    client: OpenAiClient,
    storage: MutableCodexAgentStorage,
): CodexAgentStateContract =
    CodexAgentState(
        client = client,
        storage = storage,
        contextPrefixProvider = TestContextPrefixProvider,
        toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
    )

internal val TestContextPrefix: AgentContextPrefix = AgentContextPrefix(
    cwd = Path("."),
    shell = Shell(ShellType.Sh, Path("sh")),
    agentMd = AgentsMdInstructions(),
    availableSkills = emptyList(),
)

internal val TestContextPrefixProvider: AgentContextPrefixProvider =
    { _ -> TestContextPrefix }
