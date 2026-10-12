package io.github.stream29.kodex.agentcontext.prefix.skill.contract

import kotlinx.io.files.Path

/** Scope controls catalog precedence without changing skill identity. */
public enum class SkillScope {
    Repo,
    User,
    System,
    Admin,
}

/**
 * Discovery authority for one skill.
 *
 * @property authorityId Host-defined filesystem authority identity.
 * @property scope Catalog precedence scope.
 * @property root Root under which the skill was discovered.
 */
public data class SkillSource(
    public val authorityId: String,
    public val scope: SkillScope,
    public val root: Path,
)

/**
 * Metadata for one skill exposed in the available-skills catalog.
 *
 * Path plus [source] is the stable identity. Names are intentionally not
 * unique because independent roots may provide same-named skills.
 *
 * @property name Canonical skill name.
 * @property description Human-readable skill description.
 * @property path Path to the skill's `SKILL.md` file.
 * @property source Discovery scope and filesystem authority.
 */
public data class AvailableSkill(
    public val name: String,
    public val description: String,
    public val path: Path,
    public val source: SkillSource,
)

/** Recoverable discovery problem associated with one candidate path. */
public data class SkillWarning(
    public val source: Path,
    public val message: String,
)
