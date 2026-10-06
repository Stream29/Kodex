package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlin.test.*

val agentRuntimeControlTest by testSuite {
    test("running including manual compact is stoppable") {
        assertEquals(AgentRuntimeControl.Stop, AgentStateValue.Compacting.runtimeControl(true))
        assertEquals(AgentRuntimeControl.Stop, AgentStateValue.UserMessage.runtimeControl(true))
        assertFalse(AgentStateValue.AssistantMessage.canEditHistory(true))
    }
    test("idle pending input can be cleared") {
        val pending = AgentStateValue.ToolPending(listOf(PendingRequestUserInputToolEvent(
            "question", arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion("id", "header", "question"))),
        )))
        assertEquals(AgentRuntimeControl.ClearPending, pending.runtimeControl(false))
        assertTrue(pending.canEditHistory(false))
        assertFalse(pending.canCompact(false))
    }
    test("idle states derive controls without an execution aggregate") {
        assertEquals(AgentRuntimeControl.Resume, AgentStateValue.AssistantMessage.runtimeControl(false))
        assertTrue(AgentStateValue.AssistantMessage.canCompact(false))
        assertFalse(AgentStateValue.ExternalWrite.canEditHistory(false))
        assertFalse(AgentStateValue.RequestResponse.Started.canEditHistory(false))
    }
}
