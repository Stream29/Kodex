package io.github.stream29.codex.lite.agentcontext.contract

/**
 * Structured host context projected into every normal Responses request.
 *
 * This object neither reads agent state, storage, or history nor constructs
 * OpenAI history items. The context renderer owns prompt rendering and message
 * role; the agent-state implementation owns when it is projected.
 *
 * @property environmentContext Current host environment data.
 * @property developerInstructions Host developer instructions. `null` means
 * this request has no developer-instruction source.
 * @property availableSkills Catalog of skills exposed to the model. An empty
 * list means no skill catalog is exposed.
 * @property agentMd AGENTS.md-derived instructions. An empty list means no
 * AGENTS.md instructions are injected.
 */
public data class AgentContextInjection(
    public val environmentContext: EnvironmentContext,
    public val developerInstructions: DeveloperInstructions? = null,
    public val availableSkills: List<AvailableSkill> = emptyList(),
    public val agentMd: List<AgentsMdInstruction> = emptyList(),
)
