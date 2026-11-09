package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent

public actual suspend fun AgentRuntime.submit(events: List<StableIndexEvent.Steerable>) {
    if (events.isEmpty()) return
    steer(events)
    if (runningTurn.value != null) return
    try {
        resume()
    } catch (_: ConcurrentAgentRuntimeResumeException) {
        // Another caller claimed the turn after steer was persisted; that active turn will consume it.
    }
}
