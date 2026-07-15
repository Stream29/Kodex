package io.github.stream29.codex.lite.agentcontext.skill.contract

import kotlinx.io.files.Path

/**
 * Metadata for one skill exposed in the available-skills catalog.
 *
 * @property name Canonical skill name.
 * @property description Human-readable skill description.
 * @property path Path to the skill's `SKILL.md` file.
 */
public data class AvailableSkill(
    public val name: String,
    public val description: String,
    public val path: Path,
)
