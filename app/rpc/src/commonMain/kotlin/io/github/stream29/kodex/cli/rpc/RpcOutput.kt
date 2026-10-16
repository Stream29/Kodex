package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One remote collector and one complete replay per active output, independent of history reads. */
internal fun CoroutineScope.projectOutput(
    index: Int,
    runtime: AgentRuntimeRpc,
    state: StateFlow<AgentStateValue>,
): StateFlow<KodexAgentStateValue> {
    var nonce = state.value.nonce()
    var events = MutableSharedFlow<ResponsesStreamEvent>(replay = Int.MAX_VALUE)
    val result = MutableStateFlow(state.value.display(events.asSharedFlow()))
    launch {
        coroutineScope {
            var collector: Job? = null
            var initialized = false
            state.collect { current ->
                val nextNonce = current.nonce()
                if (!initialized || nextNonce != nonce) {
                    collector?.cancelAndJoin()
                    if (initialized) events = MutableSharedFlow(replay = Int.MAX_VALUE)
                    nonce = nextNonce
                    initialized = true
                    collector = nextNonce?.let { capturedNonce ->
                        val capturedEvents = events
                        launch {
                            try {
                                runtime.currentFlow(index, capturedNonce).collect(capturedEvents::emit)
                            } catch (_: NoMatchException) {
                                // Only a new state can supersede this expired reference.
                            }
                        }
                    }
                }
                result.value = current.display(events.asSharedFlow())
            }
        }
    }
    return result.asStateFlow()
}

private fun AgentStateValue.nonce(): Long? = when (this) {
    is AgentStateValue.RequestResponse.Message -> nonce
    is AgentStateValue.RequestResponse.AgentMessage -> nonce
    is AgentStateValue.RequestResponse.Reasoning -> nonce
    is AgentStateValue.RequestResponse.ToolCall -> nonce
    is AgentStateValue.RequestResponse.Unknown -> nonce
    else -> null
}

private fun AgentStateValue.display(events: SharedFlow<ResponsesStreamEvent>): KodexAgentStateValue = when (this) {
    AgentStateValue.Empty -> KodexAgentStateValue.Empty
    AgentStateValue.UserMessage -> KodexAgentStateValue.UserMessage
    AgentStateValue.AssistantMessage -> KodexAgentStateValue.AssistantMessage
    is AgentStateValue.ToolPending -> KodexAgentStateValue.ToolPending(this.events)
    AgentStateValue.ToolCompleted -> KodexAgentStateValue.ToolCompleted
    AgentStateValue.ExternalWrite -> KodexAgentStateValue.ExternalWrite
    AgentStateValue.Compacting -> KodexAgentStateValue.Compacting
    AgentStateValue.RequestResponse.Started -> KodexAgentStateValue.RequestResponse.Started
    is AgentStateValue.RequestResponse.Message -> KodexAgentStateValue.RequestResponse.Message(events)
    is AgentStateValue.RequestResponse.AgentMessage -> KodexAgentStateValue.RequestResponse.AgentMessage(events)
    is AgentStateValue.RequestResponse.Reasoning -> KodexAgentStateValue.RequestResponse.Reasoning(events)
    is AgentStateValue.RequestResponse.ToolCall -> KodexAgentStateValue.RequestResponse.ToolCall(events)
    is AgentStateValue.RequestResponse.Unknown -> KodexAgentStateValue.RequestResponse.Unknown(events)
}
