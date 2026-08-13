package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.agent.contract.AgentExecutionCapabilities
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRuntimeControlTest {
    @Test
    fun activeTurnIsStoppable() {
        assertEquals(
            AgentRuntimeControl.Stop,
            execution(canCancel = true).runtimeControl(),
        )
    }

    @Test
    fun idleToolPendingStateCanBeCleared() {
        assertEquals(
            AgentRuntimeControl.ClearPending,
            execution(canClearPending = true).runtimeControl(),
        )
    }

    @Test
    fun otherIdleStatesCanBeResumed() {
        assertEquals(
            AgentRuntimeControl.Resume,
            execution(canResume = true).runtimeControl(),
        )
    }
}

private fun execution(
    canCancel: Boolean = false,
    canClearPending: Boolean = false,
    canResume: Boolean = false,
): AgentExecutionState = AgentExecutionState(
    phase = AgentExecutionPhase.AssistantMessage,
    latestStorageIndex = 0,
    capabilities = AgentExecutionCapabilities(
        canCancel = canCancel,
        canClearPending = canClearPending,
        canResume = canResume,
    ),
)
