package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewState
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRuntimeControlTest {
    @Test
    fun activeTurnIsStoppableEvenWhenToolsArePending() {
        assertEquals(
            AgentRuntimeControl.Stop,
            runtimeState(
                agentState = toolPendingState(),
                running = true,
            ).runtimeControl(),
        )
    }

    @Test
    fun idleToolPendingStateCanBeCleared() {
        assertEquals(
            AgentRuntimeControl.ClearPending,
            runtimeState(agentState = toolPendingState()).runtimeControl(),
        )
    }

    @Test
    fun otherIdleStatesCanBeResumed() {
        assertEquals(
            AgentRuntimeControl.Resume,
            runtimeState(agentState = KodexAgentStateValue.UserMessage).runtimeControl(),
        )
    }
}

private fun runtimeState(
    agentState: KodexAgentStateValue,
    running: Boolean = false,
): AgentRuntimeViewState = AgentRuntimeViewState(
    agentId = "agent",
    latestIndex = 0,
    agentState = agentState,
    running = running,
)

private fun toolPendingState(): KodexAgentStateValue.ToolPending =
    KodexAgentStateValue.ToolPending(
        listOf(
            PendingCustomToolEvent(
                callId = "call",
                name = "tool",
                input = "",
            ),
        ),
    )
