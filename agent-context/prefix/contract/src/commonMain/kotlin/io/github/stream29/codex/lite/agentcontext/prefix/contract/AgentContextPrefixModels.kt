package io.github.stream29.codex.lite.agentcontext.prefix.contract

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
/** One raw source contributing to AGENTS.md instructions. */
public sealed interface AgentsMdInstruction {
    /** Model-visible instruction text from this source. */
    public val text: String

    /**
     * User-level AGENTS.md instructions loaded before project instructions.
     *
     * This is not a conversation user message.
     *
     * @property source Source AGENTS.md file.
     */
    public data class User(
        public val source: Path,
        override val text: String,
    ) : AgentsMdInstruction

    /**
     * Instructions loaded from one project AGENTS.md file.
     *
     * @property source Exact AGENTS.md file, distinct from [cwd].
     * @property environmentId Host-defined execution environment identity.
     * @property cwd Working directory selected in that environment.
     */
    public data class Project(
        public val source: Path,
        public val environmentId: String,
        public val cwd: Path,
        override val text: String,
    ) : AgentsMdInstruction

    /** Instructions without an AGENTS.md file source. */
    public data class Internal(
        override val text: String,
    ) : AgentsMdInstruction
}

/**
 * Current host environment data visible to the model.
 *
 * This deliberately excludes sandbox policy; that policy belongs to runtime
 * enforcement rather than this context contract.
 *
 * @property environments Selected execution environments.
 * @property currentDate Current civil date.
 * @property timeZone Current time zone.
 */
public data class EnvironmentContext(
    public val environments: List<AgentEnvironment>,
    public val currentDate: LocalDate,
    public val timeZone: TimeZone,
) {
    init {
        val environmentIds = environments.map(AgentEnvironment::id)
        require(environmentIds.distinct().size == environmentIds.size) {
            "Environment ids must be unique."
        }
    }
}

/**
 * One selected execution environment.
 *
 * @property id Stable host-defined environment identity.
 * @property cwd Current working directory in that environment.
 * @property shell Preferred shell name.
 */
public data class AgentEnvironment(
    public val id: String,
    public val cwd: Path,
    public val shell: String,
)
