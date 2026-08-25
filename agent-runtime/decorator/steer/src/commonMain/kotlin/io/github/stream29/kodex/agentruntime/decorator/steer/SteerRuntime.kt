package io.github.stream29.kodex.agentruntime.decorator.steer

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import kotlinx.coroutines.CancellationException

/**
 * Delivers pending input that belongs to the current logical turn.
 *
 * Install this directly outside the compaction runtime and inside tool
 * handling. Pending input is delivered before the first delegation and again
 * after every appendable delegation result. A late delivery starts another
 * inner resume within the same outer turn; a tool continuation still provides
 * the next delivery boundary when the state is not appendable.
 */
public class SteerRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    private val logger: KLogger,
    private val steerProvider: SteerProvider,
) : ResumableAgentLayer by delegate {
    override suspend fun resume() {
        deliverPendingSteerIfAllowed()
        while (true) {
            delegate.resume()
            if (!deliverPendingSteerIfAllowed()) return
        }
    }

    private suspend fun deliverPendingSteerIfAllowed(): Boolean {
        if (!state.value.canAppendUserMessage) return false
        return deliverPendingSteer()
    }

    private suspend fun deliverPendingSteer(): Boolean {
        try {
            val inputs = steerProvider.take()
            if (inputs.isEmpty()) return false
            logger.info { "Agent steer delivery started (${inputs.size} input(s))." }
            delegate.injectHistory(inputs)
            logger.info { "Agent steer delivery completed (${inputs.size} input(s))." }
            return true
        } catch (cancellation: CancellationException) {
            logger.info { "Agent steer delivery cancelled." }
            throw cancellation
        } catch (failure: Throwable) {
            logger.error(failure) { "Agent steer delivery failed." }
            throw failure
        }
    }
}

/**
 * Adds delivery for clean input inserted into the current logical turn.
 *
 * Compose this after the compaction runtime and before tool handling.
 *
 * @param logger Agent-scoped logger for steer delivery.
 */
public fun ResumableAgentLayer.steerRuntime(
    logger: KLogger,
    steerProvider: SteerProvider,
): SteerRuntime = SteerRuntime(this, logger, steerProvider)
