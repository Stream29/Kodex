package io.github.stream29.kodex.desktop.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.desktop.history.AgentHistoryDesktopUiState

internal class AgentHistoryDesktopUiStateRegistry {
    private val states =
        mutableMapOf<AgentHistoryDesktopUiStateKey, AgentHistoryDesktopUiState>()

    fun stateFor(sessionIndex: Int, agentId: String): AgentHistoryDesktopUiState =
        states.getOrPut(
            AgentHistoryDesktopUiStateKey(sessionIndex, agentId),
            ::AgentHistoryDesktopUiState,
        )

    fun retain(topologies: Map<Int, Set<String>>) {
        states.keys.removeAll { stateKey ->
            val agentIds = topologies[stateKey.sessionIndex]
            agentIds == null || stateKey.agentId !in agentIds
        }
    }
}

@Composable
internal fun rememberAgentHistoryDesktopUiStateRegistry(
    tabs: List<SessionViewModel>,
): AgentHistoryDesktopUiStateRegistry {
    val registry = remember { AgentHistoryDesktopUiStateRegistry() }
    val topologies = linkedMapOf<Int, Set<String>>()
    tabs.filterIsInstance<PersistedSessionViewModel>().forEach { session ->
        key(session) {
            val topology by session.topology.collectAsState()
            topologies[session.sessionIndex] =
                topology.nodes.mapTo(linkedSetOf()) { node -> node.address.agentId }
        }
    }
    SideEffect { registry.retain(topologies) }
    return registry
}

private data class AgentHistoryDesktopUiStateKey(
    val sessionIndex: Int,
    val agentId: String,
)
