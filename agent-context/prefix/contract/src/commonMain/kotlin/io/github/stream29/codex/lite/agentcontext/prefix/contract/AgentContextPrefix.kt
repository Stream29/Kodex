package io.github.stream29.codex.lite.agentcontext.prefix.contract

import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.utils.shellclient.Shell
import kotlinx.io.files.Path

/**
 * Structured transient context resolved for one Responses request.
 *
 * @property cwd Agent working directory captured from its current settings.
 * @property shell Application-wide shell captured from context settings.
 * @property agentMd Current AGENTS.md-derived instruction sources.
 * @property availableSkills Current skill metadata catalog visible to the model.
 */
public data class AgentContextPrefix(
    public val cwd: Path,
    public val shell: Shell,
    public val agentMd: AgentsMdInstructions,
    public val availableSkills: List<AvailableSkill>,
)
