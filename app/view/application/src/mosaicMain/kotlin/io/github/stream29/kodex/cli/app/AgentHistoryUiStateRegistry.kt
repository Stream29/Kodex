package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.cli.history.AgentHistoryUiState

internal class AgentHistoryUiStateRegistry {
    private val states = mutableMapOf<AgentHistoryUiStateKey, AgentHistoryUiState>()

    fun stateFor(sessionIndex: Int, agentId: String): AgentHistoryUiState =
        states.getOrPut(AgentHistoryUiStateKey(sessionIndex, agentId), ::AgentHistoryUiState)

    fun retain(topologies: Map<Int, Set<String>>) {
        states.keys.removeAll { stateKey ->
            val agentIds = topologies[stateKey.sessionIndex]
            agentIds == null || stateKey.agentId !in agentIds
        }
    }
}

@Composable
internal fun rememberAgentHistoryUiStateRegistry(
    tabs: List<SessionViewModel>,
): AgentHistoryUiStateRegistry {
    val registry = remember { AgentHistoryUiStateRegistry() }
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

private data class AgentHistoryUiStateKey(
    val sessionIndex: Int,
    val agentId: String,
)
