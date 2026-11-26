package io.github.stream29.kodex.agentcontext.prefix.contract

import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.io.files.Path

/** Stable identity metadata for the Agent that owns one context prefix. */
public data class AgentSessionMeta(
    public val agentId: Int?,
    public val name: String,
)

/**
 * Structured transient context resolved for one Responses request.
 *
 * @property cwd Agent working directory captured from its current settings.
 * @property shell Application-wide shell captured from context settings.
 * @property agentMd Current AGENTS.md-derived instruction sources.
 * @property availableSkills Current skill metadata catalog visible to the model.
 * @property sessionMeta Stable identity metadata for the current Session.
 * @property requestUserInputMode Current user-question policy controlling whether `kodex ask` is allowed.
 */
public data class AgentContextPrefix(
    public val cwd: Path,
    public val shell: Shell,
    public val agentMd: AgentsMdInstructions,
    public val availableSkills: List<AvailableSkill>,
    public val sessionMeta: AgentSessionMeta,
    public val requestUserInputMode: RequestUserInputMode = RequestUserInputMode.AskUser,
)
