package io.github.stream29.codex.lite.agentruntime.steer

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.canAppendUserMessage
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Delivers user input that belongs to the current logical turn.
 *
 * Install this directly outside the compaction runtime and inside tool
 * handling. Each collected [resume] requests at most one message from
 * [steerProvider] before delegating, so a tool continuation provides the next
 * delivery boundary.
 */
public class SteerRuntime internal constructor(
    private val delegate: CodexAgentRuntime,
    private val steerProvider: SteerProvider,
) : CodexAgentRuntime by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        if (state.value.canAppendUserMessage) {
            val content = steerProvider.take()
            if (content.isNotEmpty()) {
                delegate.appendUserMessage(content)
            }
        }
        emitAll(delegate.resume())
    }
}

/**
 * Adds delivery for merged user input inserted into the current logical turn.
 *
 * Compose this after the compaction runtime and before tool handling.
 */
public fun CodexAgentRuntime.steerRuntime(
    steerProvider: SteerProvider,
): SteerRuntime = SteerRuntime(this, steerProvider)
