package io.github.stream29.codex.lite.agentruntime.decorator.steer

import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentstate.contract.canAppendUserMessage
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
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
    private val steerProvider: SteerProvider,
) : ResumableAgentLayer by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        if (state.value.canAppendUserMessage) {
            val inputs = steerProvider.take()
            if (inputs.isNotEmpty()) {
                delegate.injectHistory(inputs)
            }
        }
        emitAll(delegate.resume())
    }
}

/**
 * Adds delivery for typed input inserted into the current logical turn.
 *
 * Compose this after the compaction runtime and before tool handling.
 */
public fun ResumableAgentLayer.steerRuntime(
    steerProvider: SteerProvider,
): SteerRuntime = SteerRuntime(this, steerProvider)
