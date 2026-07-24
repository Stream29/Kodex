package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.environment.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

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
    environmentContext = EnvironmentContext(
        environments = emptyList(),
        currentDate = LocalDate(2026, 7, 15),
        timeZone = TimeZone.UTC,
    ),
    agentMd = emptyList(),
    availableSkills = emptyList(),
)

internal val TestContextPrefixProvider: AgentContextPrefixProvider =
    AgentContextPrefixProvider { TestContextPrefix }
