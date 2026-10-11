package io.github.stream29.kodex.agentruntime.steer

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate

/** Observes and claims the host's existing queue without a second input source. */
public class StateFlowSteerProvider(
    private val pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>,
) : SteerProvider {
    override suspend fun waitForSteer() {
        pendingSteer.first { it.isNotEmpty() }
    }

    override suspend fun take(): List<StableIndexEvent.Steerable> {
        currentCoroutineContext().ensureActive()
        return pendingSteer.getAndUpdate { emptyList() }
    }
}
