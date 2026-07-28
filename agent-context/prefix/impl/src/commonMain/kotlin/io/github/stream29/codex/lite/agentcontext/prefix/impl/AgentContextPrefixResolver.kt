package io.github.stream29.codex.lite.agentcontext.prefix.impl

import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.filesystem.loadAgentsMd
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.contract.AgentContextSettings
import io.github.stream29.codex.lite.agentcontext.skill.filesystem.FileSystemSkillsResolver
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * Resolves application-wide and Agent-specific data into one request prefix.
 *
 * AgentState owns this helper rather than accepting an executable context
 * provider. Each [resolve] call captures one complete [contextSettings]
 * snapshot together with the supplied Agent settings.
 */
public class AgentContextPrefixResolver(
    private val contextSettings: StateFlow<AgentContextSettings>,
    userHome: Path = requireUserHomeDirectory(),
) {
    private val skillsResolver = FileSystemSkillsResolver(
        contextSettings = contextSettings,
        userHome = userHome,
    )

    /** Captures global state and resolves Agent-specific context for [settings]. */
    public suspend fun resolve(settings: CodexAgentSettings): AgentContextPrefix {
        val context = contextSettings.value
        val cwd = settings.cwd
        val agentsMd = loadAgentsMd(context.codexHome, cwd)
        val skills = skillsResolver.resolve(cwd, context)
        return AgentContextPrefix(
            cwd = cwd,
            shell = context.shell,
            agentMd = agentsMd.instructions,
            availableSkills = skills.skills,
        )
    }
}
