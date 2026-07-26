package io.github.stream29.codex.lite.agentcontext.prefix.contract

import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.utils.shellclient.Shell
import kotlinx.io.files.Path

/**
 * Structured request prefix captured for one logical user turn.
 *
 * @property cwd Agent working directory captured from its current settings.
 * @property shell Application-wide shell captured with this prefix.
 * @property agentMd Current AGENTS.md-derived instruction sources.
 * @property availableSkills Current skill metadata catalog visible to the model.
 */
public data class AgentContextPrefix(
    public val cwd: Path,
    public val shell: Shell,
    public val agentMd: AgentsMdInstructions,
    public val availableSkills: List<AvailableSkill>,
)

/**
 * Supplies the complete structured prefix for normal Responses requests.
 *
 * This provider is the abstraction boundary between AgentState and the
 * concrete host environment and application settings. An implementation
 * observes and combines those external sources, while AgentState only supplies
 * its current Agent settings and consumes the resulting [AgentContextPrefix].
 * AgentState therefore does not depend on a frontend settings model,
 * environment APIs, or their observation and persistence mechanisms.
 *
 * AgentState invokes the provider with the settings visible at the projected
 * storage index. A turn-aware provider may return the same frozen value for
 * every request in one logical user turn. A provider neither reads agent
 * state, storage, or history nor constructs OpenAI items.
 */
public typealias AgentContextPrefixProvider =
    suspend (settings: CodexAgentSettings) -> AgentContextPrefix
