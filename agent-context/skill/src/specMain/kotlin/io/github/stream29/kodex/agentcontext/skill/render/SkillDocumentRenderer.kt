package io.github.stream29.kodex.agentcontext.skill.render

import io.github.stream29.kodex.agentcontext.skill.contract.SkillDocument

/** Renders one selected skill as durable contextual user input. */
public expect fun SkillDocument.render(): String
