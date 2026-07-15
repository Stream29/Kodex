package io.github.stream29.codex.lite.agentcontext.skill.render

import io.github.stream29.codex.lite.agentcontext.promptdsl.promptXml
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill

private const val SkillsIntro: String =
    "A skill is a set of local instructions stored in a `SKILL.md` file. Below is the list of skills available to the agent. Each entry includes a name, description, and path."

/** Renders the model-visible catalog for the supplied available skills. */
public fun List<AvailableSkill>.render(): String {
    val skills = this
    return promptXml(indented = false) {
        tag("skills_instructions") {
            text("\n## Skills\n")
            text(SkillsIntro)
            text("\n### Available skills\n")
            skills.forEach { skill ->
                text("- name: ")
                text(skill.name)
                text("\n  description: ")
                text(skill.description)
                text("\n  path: ")
                text(skill.path.toString())
                text("\n")
            }
        }
    }
}
