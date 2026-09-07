package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.agent.contract.AgentExecutionCapabilities
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val agentRuntimeControlTest by testSuite {
    test("activeTurnIsStoppable") {
        assertEquals(
            AgentRuntimeControl.Stop,
            execution(canCancel = true).runtimeControl(),
        )
    }

    test("idleToolPendingStateCanBeCleared") {
        assertEquals(
            AgentRuntimeControl.ClearPending,
            execution(canClearPending = true).runtimeControl(),
        )
    }

    test("otherIdleStatesCanBeResumed") {
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
