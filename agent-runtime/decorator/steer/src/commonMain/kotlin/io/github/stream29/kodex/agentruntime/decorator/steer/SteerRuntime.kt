package io.github.stream29.kodex.agentruntime.decorator.steer

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Delivers pending input that belongs to the current logical turn.
 *
 * Install this directly outside the compaction runtime and inside tool
 * handling. Each collected [resume] requests at most one message from
 * [steerProvider] before delegating, so a tool continuation provides the next
 * delivery boundary.
 */
public class SteerRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    private val logger: KLogger,
    private val steerProvider: SteerProvider,
) : ResumableAgentLayer by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        if (state.value.canAppendUserMessage) {
            deliverPendingSteer()
        }
        emitAll(delegate.resume())
    }

    private suspend fun deliverPendingSteer() {
        try {
            val inputs = steerProvider.take()
            if (inputs.isEmpty()) return
            logger.info { "Agent steer delivery started (${inputs.size} input(s))." }
            delegate.injectHistory(inputs)
            logger.info { "Agent steer delivery completed (${inputs.size} input(s))." }
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
