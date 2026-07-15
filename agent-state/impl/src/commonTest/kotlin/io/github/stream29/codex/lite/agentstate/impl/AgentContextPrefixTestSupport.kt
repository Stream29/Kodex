package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill

internal fun fixedAgentContextPrefixProvider(
    environmentContext: EnvironmentContext,
    availableSkills: List<AvailableSkill> = emptyList(),
    agentMd: List<AgentsMdInstruction> = emptyList(),
): AgentContextPrefixProvider {
    val fixedEnvironmentContext = environmentContext
    val fixedAvailableSkills = availableSkills
    val fixedAgentMd = agentMd

    return object : AgentContextPrefixProvider {
        override val environmentContext: EnvironmentContext = fixedEnvironmentContext

        override val availableSkills: List<AvailableSkill> = fixedAvailableSkills

        override val agentMd: List<AgentsMdInstruction> = fixedAgentMd
    }
}
