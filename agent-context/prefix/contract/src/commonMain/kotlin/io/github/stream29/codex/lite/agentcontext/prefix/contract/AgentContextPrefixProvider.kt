package io.github.stream29.codex.lite.agentcontext.prefix.contract

import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill

/**
 * Supplies structured host context for the transient prefix of normal Responses
 * requests.
 *
 * AgentState reads every property when it projects a request, so an
 * implementation's getters may reflect later runtime, filesystem,
 * configuration, or capability changes. This provider neither reads agent
 * state, storage, or history nor constructs OpenAI history items.
 *
 * @property environmentContext Current host environment data visible to the model.
 * @property availableSkills Current catalog of skills exposed to the model.
 * @property agentMd Current AGENTS.md-derived instruction sources.
 */
public interface AgentContextPrefixProvider {
    public val environmentContext: EnvironmentContext

    public val availableSkills: List<AvailableSkill>

    public val agentMd: List<AgentsMdInstruction>
}
