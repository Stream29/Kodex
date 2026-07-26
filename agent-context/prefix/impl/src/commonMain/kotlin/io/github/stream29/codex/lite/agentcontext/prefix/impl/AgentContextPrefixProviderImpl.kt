package io.github.stream29.codex.lite.agentcontext.prefix.impl

import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.filesystem.loadAgentsMd
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextSettings
import io.github.stream29.codex.lite.agentcontext.skill.contract.ResolvedSkills
import io.github.stream29.codex.lite.agentcontext.skill.contract.SkillsResolver
import io.github.stream29.codex.lite.agentcontext.skill.filesystem.FileSystemSkillsResolver
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * Joins application-wide context with one Agent's current settings.
 *
 * [contextSettings] remains live for the lifetime of this provider. Each
 * invocation captures one complete application-wide snapshot together with
 * the supplied Agent settings.
 */
public class AgentContextPrefixProviderImpl(
    private val contextSettings: StateFlow<AgentContextSettings>,
) : AgentContextPrefixProvider, SkillsResolver {
    private val skillsResolver = FileSystemSkillsResolver(
        contextSettings = contextSettings,
        userHome = requireUserHomeDirectory(),
    )

    override suspend fun resolve(cwd: Path): ResolvedSkills =
        skillsResolver.resolve(cwd)

    /** Captures global state and resolves Agent-specific context for [settings]. */
    override suspend operator fun invoke(settings: CodexAgentSettings): AgentContextPrefix {
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
