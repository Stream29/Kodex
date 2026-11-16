package io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract

import kotlinx.io.files.Path

/**
 * One raw source contributing to AGENTS.md instructions.
 *
 * @property source Exact discovered source file.
 * @property text Model-visible instruction text from this source.
 */
public data class AgentsMdInstruction(
    public val source: Path,
    public val text: String,
)

/**
 * Ordered AGENTS.md instructions grouped by their application scope.
 *
 * @property globalInstructions Global documents ordered across configured global roots.
 * @property projectInstructions Project documents ordered from the nearest Git
 * root to the selected working directory.
 */
public data class AgentsMdInstructions(
    public val globalInstructions: List<AgentsMdInstruction> = emptyList(),
    public val projectInstructions: List<AgentsMdInstruction> = emptyList(),
)
