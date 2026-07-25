package io.github.stream29.codex.lite.tool.multiagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val multiAgentToolsTest by testSuite {
    test("specs expose the six Multi-agent V2 function tools") {
        assertEquals(
            listOf(
                "spawn_agent",
                "send_message",
                "followup_task",
                "wait_agent",
                "interrupt_agent",
                "list_agents",
            ),
            MultiAgentTools.specs.map(ResponsesApiTool::name),
        )
        assertTrue(MultiAgentTools.specs.none(ResponsesApiTool::strict))
        assertEquals(null, MultiAgentTools.sendMessageSpec.outputSchema)
        assertEquals(null, MultiAgentTools.followupTaskSpec.outputSchema)
        assertEquals(10_000L, MultiAgentTools.MinWaitTimeoutMillis)
        assertEquals(30_000L, MultiAgentTools.DefaultWaitTimeoutMillis)
        assertEquals(3_600_000L, MultiAgentTools.MaxWaitTimeoutMillis)

        val spawnParameters = MultiAgentTools.spawnAgentSpec.parameters.jsonObject()
        assertEquals(JsonPrimitive(false), spawnParameters["additionalProperties"])
        assertEquals(
            listOf("task_name", "message"),
            spawnParameters.getValue("required").jsonArray.map { element ->
                element.jsonPrimitive.content
            },
        )
        assertEquals(
            JsonPrimitive("integer"),
            MultiAgentTools.waitAgentSpec.parameters.jsonObject()
                .getValue("properties").jsonObject
                .getValue("timeout_ms").jsonObject
                .getValue("type"),
        )
        assertFalse("required" in MultiAgentTools.listAgentsSpec.parameters.jsonObject())
    }

    test("status serializer matches the Rust string and object union") {
        val cases = listOf(
            MultiAgentStatus.PendingInit to "\"pending_init\"",
            MultiAgentStatus.Running to "\"running\"",
            MultiAgentStatus.Interrupted to "\"interrupted\"",
            MultiAgentStatus.Completed(null) to "{\"completed\":null}",
            MultiAgentStatus.Completed("done") to "{\"completed\":\"done\"}",
            MultiAgentStatus.Errored("failed") to "{\"errored\":\"failed\"}",
            MultiAgentStatus.Shutdown to "\"shutdown\"",
            MultiAgentStatus.NotFound to "\"not_found\"",
        )

        cases.forEach { (status, expectedJson) ->
            val encoded = OpenAiJsonCodec.encodeToString(MultiAgentStatus.serializer(), status)
            assertEquals(expectedJson, encoded)
            assertEquals(
                status,
                OpenAiJsonCodec.decodeFromString(MultiAgentStatus.serializer(), encoded),
            )
        }
    }

}

private fun ObjectPropertyDefinition.jsonObject() = OpenAiJsonCodec.parseToJsonElement(
    OpenAiJsonCodec.encodeToString(this),
).jsonObject
