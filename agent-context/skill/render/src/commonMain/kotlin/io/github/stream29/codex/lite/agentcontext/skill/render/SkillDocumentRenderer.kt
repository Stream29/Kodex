package io.github.stream29.codex.lite.agentcontext.skill.render

import io.github.stream29.codex.lite.agentcontext.promptdsl.promptXml
import io.github.stream29.codex.lite.agentcontext.skill.contract.SkillDocument

/** Renders one selected skill as durable contextual user input. */
public fun SkillDocument.render(): String = promptXml(indented = false) {
    tag("skill") {
        rawText("\n")
        tag("name") { text(skill.name) }
        rawText("\n")
        tag("path") { text(skill.path.toString()) }
        rawText("\n")
        rawText(instructions)
        rawText("\n")
    }
}
