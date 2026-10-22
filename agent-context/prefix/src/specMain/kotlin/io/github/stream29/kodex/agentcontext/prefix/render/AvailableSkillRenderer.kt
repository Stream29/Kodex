package io.github.stream29.kodex.agentcontext.prefix.render

import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill

/** Renders the model-visible catalog for the supplied available skills. */
public expect fun List<AvailableSkill>.render(): String
