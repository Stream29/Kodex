package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.cli.agent.toRenderState
import io.github.stream29.kodex.cli.agent.label

/** Terminal-ready tree lines; layout and interaction remain Mosaic concerns. */
public fun RootSessionViewState.renderTreeLines(): List<String> = agents.map { entry ->
    buildString {
        repeat(entry.depth) { append("  ") }
        if (entry.selected) append("> ") else append("  ")
        append(entry.viewModel.state.value.durable.settings?.threadName ?: entry.agentId)
        append(" — ")
        append(
            entry.viewModel.state.value.toRenderState().label(
                entry.viewModel.session.runtime.unifiedExecToolClient.activeSessions.value.size,
            ),
        )
    }
}
