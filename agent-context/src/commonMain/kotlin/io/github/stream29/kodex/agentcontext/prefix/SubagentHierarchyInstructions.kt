package io.github.stream29.kodex.agentcontext.prefix

public actual fun subagentHierarchyInstructions(agentPath: List<Int>): String {
    require(agentPath.size >= 2 && agentPath.all { it >= 0 } && agentPath.distinct().size == agentPath.size)
    val selfId = agentPath.last()
    val parentId = agentPath[agentPath.lastIndex - 1]
    return "You are subagent #$selfId, delegated by Agent #$parentId. " +
        "Your Agent hierarchy (root to you) is ${agentPath.joinToString(" → ")}. " +
        "Work from the task you received; the parent conversation is not part of your history. " +
        "Merely sending a final response does not complete your task or notify Agent #$parentId; " +
        "when your delegated work is finished, run `kodex agent complete` before finishing so your parent is notified."
}
