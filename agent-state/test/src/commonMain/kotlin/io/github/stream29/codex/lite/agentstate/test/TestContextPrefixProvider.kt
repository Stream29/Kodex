package io.github.stream29.codex.lite.agentstate.test

import io.github.stream29.codex.lite.agentcontext.environment.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Fixed empty host context for tests that do not exercise context projection. */
public val TestContextPrefixProvider: AgentContextPrefixProvider = AgentContextPrefixProvider {
    AgentContextPrefix(
        environmentContext = EnvironmentContext(
            environments = emptyList(),
            currentDate = LocalDate(2026, 7, 15),
            timeZone = TimeZone.UTC,
        ),
        agentMd = emptyList(),
        availableSkills = emptyList(),
    )
}
