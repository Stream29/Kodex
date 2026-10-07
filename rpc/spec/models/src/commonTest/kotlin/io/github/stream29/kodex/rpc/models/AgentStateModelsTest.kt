package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

val agentStateModelsTest by testSuite {
    val serializer = AgentStateValue.serializer()

    test("all atomic state branches preserve their original value shape") {
        val states = listOf(
            AgentStateValue.Empty,
            AgentStateValue.UserMessage,
            AgentStateValue.AssistantMessage,
            AgentStateValue.ToolPending(
                listOf(
                    PendingFunctionToolEvent(
                        callId = "call-1",
                        name = "function",
                        arguments = Json.parseToJsonElement("""{"query":"你好"}"""),
                    ),
                    PendingCustomToolEvent(callId = "call-2", name = "custom", input = "Input"),
                ),
            ),
            AgentStateValue.ToolCompleted,
            AgentStateValue.ExternalWrite,
            AgentStateValue.Compacting,
            AgentStateValue.RequestResponse.Started,
            AgentStateValue.RequestResponse.Message(1),
            AgentStateValue.RequestResponse.AgentMessage(2),
            AgentStateValue.RequestResponse.Reasoning(3),
            AgentStateValue.RequestResponse.ToolCall(4),
            AgentStateValue.RequestResponse.Unknown(5),
        )
        for (state in states) {
            assertEquals(state, Json.decodeFromString(serializer, Json.encodeToString(serializer, state)))
        }
    }

    test("output carries only kind and opaque marker while Started has no stream") {
        assertEquals(
            setOf("type", "nonce"),
            Json.encodeToJsonElement(serializer, AgentStateValue.RequestResponse.Message(42)).jsonObject.keys,
        )
        assertEquals(
            setOf("type"),
            Json.encodeToJsonElement(serializer, AgentStateValue.RequestResponse.Started).jsonObject.keys,
        )
    }

    test("marker preserves all Long values and distinguishes replacement output") {
        for (nonce in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
            val state = AgentStateValue.RequestResponse.Message(nonce)
            assertEquals(state, Json.decodeFromString(serializer, Json.encodeToString(serializer, state)))
        }
        assertNotEquals(
            AgentStateValue.RequestResponse.Message(10),
            AgentStateValue.RequestResponse.Message(11),
        )
    }

    test("missing marker unknown kind and empty pending tools are rejected") {
        for (encoded in listOf(
            """{"type":"request_message"}""",
            """{"type":"request_message","nonce":null}""",
            """{"type":"nonexistent"}""",
            """{"type":"tool_pending","events":[]}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                Json.decodeFromString(serializer, encoded)
            }
        }
        assertFailsWith<IllegalArgumentException> { AgentStateValue.ToolPending(emptyList()) }
    }
}
