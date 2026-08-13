package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState
import io.github.stream29.kodex.cli.agent.label

/** Terminal-ready tree lines; layout and interaction remain Mosaic concerns. */
public fun PersistedSessionTopologyState.renderTreeLines(
    selectedAddress: AgentAddress,
): List<String> = nodes.map { node ->
    buildString {
        repeat(node.depth) { append("  ") }
        if (node.address == selectedAddress) append("> ") else append("  ")
        append(node.threadName ?: node.address.agentId)
        append(" — ")
        append(node.phase.label())
    }
}
