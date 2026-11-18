package io.github.stream29.kodex.agentcontext.prefix

public actual fun subagentHierarchyInstructions(agentPath: List<Int>): String {
    require(agentPath.size >= 2 && agentPath.all { it >= 0 } && agentPath.distinct().size == agentPath.size)
    return "You are subagent #${agentPath.last()}, delegated by Agent #${agentPath[agentPath.lastIndex - 1]}. " +
        "Your Agent hierarchy (root to you) is ${agentPath.joinToString(" → ")}. " +
        "Work from the task you received; the parent conversation is not part of your history."
}
