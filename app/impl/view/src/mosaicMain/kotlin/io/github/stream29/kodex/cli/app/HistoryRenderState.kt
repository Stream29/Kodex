package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.cli.history.AgentHistoryViewState

/**
 * Retains only widget state for the exact History children of currently owned tabs.
 * Observing every tab withdraws the old state even when its binding changes while hidden.
 * Removing this root composition releases these states; no backend operation is closed here.
 */
@Composable
internal fun rememberHistoryRenderStates(
    tabs: List<SessionViewModel>,
): MutableList<Pair<AgentHistoryViewModel, AgentHistoryViewState>> {
    val histories = buildList {
        tabs.filterIsInstance<PersistedSessionViewModel>().forEach { tab ->
            key(tab) {
                tab.rootAgent.collectAsState().value?.history?.let(::add)
            }
        }
    }
    val states = remember { mutableListOf<Pair<AgentHistoryViewModel, AgentHistoryViewState>>() }
    pruneHistoryRenderStates(states, histories)
    return states
}

/** Identity, not storage index or equality, defines a retained renderer owner. */
internal fun pruneHistoryRenderStates(
    states: MutableList<Pair<AgentHistoryViewModel, AgentHistoryViewState>>,
    histories: List<AgentHistoryViewModel>,
) {
    states.removeAll { (previous, _) -> histories.none { current -> current === previous } }
}

internal fun historyRenderStateFor(
    states: MutableList<Pair<AgentHistoryViewModel, AgentHistoryViewState>>,
    history: AgentHistoryViewModel,
): AgentHistoryViewState =
    states.firstOrNull { (owner, _) -> owner === history }?.second
        ?: AgentHistoryViewState().also { states += history to it }
