package io.github.stream29.kodex.agentcontext.prefix

/** Renders real stored ancestry for a child prompt; this string is not an Agent address. */
public expect fun subagentHierarchyInstructions(agentPath: List<Int>): String
