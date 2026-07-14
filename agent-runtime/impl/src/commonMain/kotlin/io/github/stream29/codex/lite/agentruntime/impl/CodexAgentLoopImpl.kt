package io.github.stream29.codex.lite.agentruntime.impl

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow

/**
 * Basic runtime with no environment-side effects.
 *
 * It handles token-limit compaction and server-requested continuation before
 * returning control. A pending tool call ends this flow so a higher runtime
 * can execute the tool through its privately held AgentState.
 */
public class CodexAgentLoopImpl(
    private val agentState: CodexAgentState,
) : CodexAgentRuntime {
    public override val state: StateFlow<CodexAgentStateValue> = agentState.state
    public override val latestIndex: StateFlow<Int> = agentState.latestIndex
    public override val storage: CodexAgentStorage = agentState.storage

    public override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        if (state.value is CodexAgentStateValue.ToolPending) {
            return@channelFlow
        }

        if (shouldAutoCompact()) {
            compactForContextLimit(RemoteCompactionV2Phase.PreTurn)
        }

        while (true) {
            var needsFollowUp = false
            agentState.requestResponseApi().collect { event ->
                if (event is ResponsesStreamEvent.Completed && event.response.endTurn == false) {
                    needsFollowUp = true
                }
                send(event)
            }

            if (state.value is CodexAgentStateValue.ToolPending || !needsFollowUp) {
                return@channelFlow
            }

            if (shouldAutoCompact()) {
                compactForContextLimit(RemoteCompactionV2Phase.MidTurn)
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun compactForContextLimit(phase: RemoteCompactionV2Phase) {
        agentState.compact(
            trigger = RemoteCompactionV2Trigger.Auto,
            reason = RemoteCompactionV2Reason.ContextLimit,
            phase = phase,
        )
    }

    private suspend fun shouldAutoCompact(): Boolean {
        val snapshotIndex = storage.latestIndex()
        if (snapshotIndex < 0 || storage.tokenCount.latestIndex() < 0) {
            return false
        }
        val tokenLimit = storage.settings[snapshotIndex].autoCompactionTokenLimit ?: return false
        return storage.tokenCount[snapshotIndex] >= tokenLimit
    }
}
