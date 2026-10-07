package io.github.stream29.kodex.agentcontext.skill.contract

import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill
import kotlinx.io.files.Path

/** Raw contents frozen when a discovered skill is selected. */
public data class SkillDocument(
    public val skill: AvailableSkill,
    public val instructions: String,
)

/** Result of reading through a captured skill authority. */
public sealed interface SkillResourceResult<out T> {
    public data class Success<T>(public val value: T) : SkillResourceResult<T>

    public data class Failure(
        public val source: Path,
        public val message: String,
    ) : SkillResourceResult<Nothing>
}
