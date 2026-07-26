package io.github.stream29.codex.lite.agentcontext.skill.contract

import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.SkillWarning
import kotlinx.io.files.Path

/** Immutable skill catalog and read authority resolved for one working directory. */
public interface ResolvedSkills {
    public val skills: List<AvailableSkill>

    public val warnings: List<SkillWarning>

    public suspend fun loadSkill(skill: AvailableSkill): SkillResourceResult<SkillDocument>

    public suspend fun readResource(
        skill: AvailableSkill,
        relativePath: Path,
    ): SkillResourceResult<ByteArray>
}

/** Resolves the skills visible from [cwd] without publishing shared mutable state. */
public fun interface SkillsResolver {
    public suspend fun resolve(cwd: Path): ResolvedSkills
}
