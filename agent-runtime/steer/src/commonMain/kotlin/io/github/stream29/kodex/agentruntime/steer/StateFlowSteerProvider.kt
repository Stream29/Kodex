@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.steer

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate

public actual class StateFlowSteerProvider actual constructor(
    private val pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>,
) : SteerProvider {
    actual override suspend fun waitForSteer() {
        pendingSteer.first { it.isNotEmpty() }
    }

    actual override suspend fun take(): List<StableIndexEvent.Steerable> {
        currentCoroutineContext().ensureActive()
        return pendingSteer.getAndUpdate { emptyList() }
    }
}
