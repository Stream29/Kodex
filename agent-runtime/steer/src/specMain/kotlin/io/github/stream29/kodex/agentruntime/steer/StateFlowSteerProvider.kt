@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.steer

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import kotlinx.coroutines.flow.MutableStateFlow

/** Observes and claims the host's existing queue without a second input source. */
public expect class StateFlowSteerProvider(
    pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>,
) : SteerProvider {
    override suspend fun waitForSteer(): Unit

    override suspend fun take(): List<StableIndexEvent.Steerable>
}
