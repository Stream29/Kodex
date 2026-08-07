package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stream29.kodex.cli.history.AgentHistoryUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class AgentHistoryUiStateRegistry {
    private val states = mutableMapOf<AgentHistoryUiStateKey, AgentHistoryUiState>()

    fun stateFor(sessionIndex: Int, agentId: String): AgentHistoryUiState =
        states.getOrPut(AgentHistoryUiStateKey(sessionIndex, agentId), ::AgentHistoryUiState)

    fun retain(topologies: Map<Int, Set<String>>) {
        states.keys.removeAll { key ->
            val sessionAgentIds = topologies[key.sessionIndex]
            sessionAgentIds == null || key.agentId !in sessionAgentIds
        }
    }
}

@Composable
internal fun rememberAgentHistoryUiStateRegistry(
    tabs: List<SessionTabViewState>,
): AgentHistoryUiStateRegistry {
    val registry = remember { AgentHistoryUiStateRegistry() }
    val topologies = linkedMapOf<Int, Set<String>>()
    tabs.forEach { tab ->
        key(tab.target) {
            when (val target = tab.target) {
                is SessionTabTarget.NewSession -> Unit
                is SessionTabTarget.OpenSession -> {
                    val rootViewModel = requireNotNull(requireNotNull(tab.rootSession).viewModel)
                    val agentIdsFlow = remember(rootViewModel) {
                        rootViewModel.state
                            .map { tree ->
                                tree.agents.mapTo(linkedSetOf()) { agent -> agent.agentId }
                            }
                            .distinctUntilChanged()
                    }
                    val agentIds by agentIdsFlow.collectAsState(
                        rootViewModel.state.value.agents
                            .mapTo(linkedSetOf()) { agent -> agent.agentId },
                    )
                    topologies[target.sessionIndex] = agentIds
                }
            }
        }
    }
    SideEffect {
        registry.retain(topologies)
    }
    return registry
}

private data class AgentHistoryUiStateKey(
    val sessionIndex: Int,
    val agentId: String,
)
