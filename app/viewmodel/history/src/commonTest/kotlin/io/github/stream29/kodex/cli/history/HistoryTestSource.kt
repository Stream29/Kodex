package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*

/** Existing window tests use a local source fixture, never a production entity factory. */
internal fun createAgentHistoryViewModel(
    agentState: KodexAgentState,
    ownerScope: CoroutineScope,
    runningTurn: StateFlow<Job?> = MutableStateFlow(null),
): AgentHistoryViewModel = createAgentHistoryViewModel(
    AgentHistorySource(agentState.storage, agentState.latestIndex, agentState.state),
    ownerScope,
    runningTurn.map { it != null }.stateIn(ownerScope, SharingStarted.Eagerly, runningTurn.value != null),
)
