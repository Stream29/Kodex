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
 * @property userInstruction Nullable because the Codex home may contain no
 * readable, nonblank `AGENTS.override.md` or `AGENTS.md`; `null` means no
 * user-level instructions apply.
 * @property projectInstructions Project documents ordered from project root to
 * the selected working directory.
 */
public data class AgentsMdInstructions(
    public val userInstruction: AgentsMdInstruction? = null,
    public val projectInstructions: List<AgentsMdInstruction> = emptyList(),
)

/** Recoverable problem observed while loading one AGENTS.md snapshot. */
public sealed interface AgentsMdWarning {
    /** Candidate path involved in the problem. */
    public val source: Path

    /** The selected source could not be read. */
    public data class ReadFailed(
        override val source: Path,
        public val message: String,
    ) : AgentsMdWarning

    /** Invalid UTF-8 was replaced while preserving usable text. */
    public data class InvalidUtf8(
        override val source: Path,
    ) : AgentsMdWarning

    /** A project source exceeded the remaining byte budget. */
    public data class Truncated(
        override val source: Path,
        public val originalByteCount: Long,
        public val acceptedByteCount: Int,
    ) : AgentsMdWarning
}

/**
 * One immutable AGENTS.md discovery result.
 *
 * @property instructions Structured model-visible instruction sources.
 * @property warnings Recoverable loading problems for this result.
 */
public data class AgentsMdSnapshot(
    public val instructions: AgentsMdInstructions,
    public val warnings: List<AgentsMdWarning>,
)
