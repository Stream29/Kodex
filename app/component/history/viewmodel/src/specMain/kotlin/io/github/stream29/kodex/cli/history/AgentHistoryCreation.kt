package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Creates the single History owner for [source]'s fixed Agent.
 *
 * [ownerScope] must be a dedicated child scope: [AgentHistoryViewModel.close] cancels it, including
 * paging, streaming observation and item payload reads. [running] belongs to the same Agent and
 * drives elapsed-turn presentation only. The caller retains storage/binding ownership.
 * The returned owner starts structural loading asynchronously; failures are published through
 * `loadState`. Construction does not synchronously read storage or throw for absent history.
 */
public expect fun createAgentHistoryViewModel(
    source: AgentHistorySource,
    ownerScope: CoroutineScope,
    running: StateFlow<Boolean>,
): AgentHistoryViewModel
