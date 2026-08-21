package io.github.stream29.kodex.agentcontext.prefix.impl

import io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem.loadAgentsMd
import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.skill.filesystem.FileSystemSkillsResolver
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolves application-wide and Agent-specific data into one request prefix.
 *
 * AgentState owns this helper rather than accepting an executable context
 * provider. Each [resolve] call captures one complete [contextSettings]
 * snapshot together with the supplied Agent settings.
 */
public class AgentContextPrefixResolver(
    private val contextSettings: StateFlow<AgentContextSettings>,
) {
    private val skillsResolver = FileSystemSkillsResolver(
        contextSettings = contextSettings,
    )

    /** Captures global state and resolves Agent-specific context for [settings]. */
    public suspend fun resolve(settings: KodexAgentSettings): AgentContextPrefix {
        val context = contextSettings.value
        val cwd = settings.cwd
        val agentsMd = loadAgentsMd(context.agentsHome, context.kodexHome, cwd)
        val skills = skillsResolver.resolve(cwd, context)
        return AgentContextPrefix(
            cwd = cwd,
            shell = context.shell,
            agentMd = agentsMd.instructions,
            availableSkills = skills.skills,
        )
    }
}
