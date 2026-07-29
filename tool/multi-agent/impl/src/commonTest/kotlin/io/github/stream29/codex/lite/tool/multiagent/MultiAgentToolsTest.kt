package io.github.stream29.codex.lite.tool.multiagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    test("list_agents omits task-message previews") {
        val agentSchema = requireNotNull(MultiAgentTools.listAgentsSpec.outputSchema)
            .jsonObject()
            .getValue("properties").jsonObject
            .getValue("agents").jsonObject
            .getValue("items").jsonObject
        assertEquals(
            listOf("agent_name", "agent_status"),
            agentSchema.getValue("required").jsonArray.map { element ->
                element.jsonPrimitive.content
            },
        )
        val agentProperties = agentSchema.getValue("properties").jsonObject
        assertFalse("last_task_message" in agentProperties)
        val statusSchema = agentProperties.getValue("agent_status").jsonObject
        assertEquals(JsonPrimitive("string"), statusSchema["type"])
        assertEquals(
            listOf("running", "idle"),
            statusSchema.getValue("enum").jsonArray.map { element -> element.jsonPrimitive.content },
        )

        val result = ListAgentsResult(
            agents = listOf(ListedAgent("/root", MultiAgentStatus.Running)),
        )
        val serializedAgent = OpenAiJsonCodec
            .parseToJsonElement(OpenAiJsonCodec.encodeToString(ListAgentsResult.serializer(), result))
            .jsonObject
            .getValue("agents").jsonArray
            .single()
            .jsonObject
        assertFalse("last_task_message" in serializedAgent)
    }

    test("status serializer matches the two turn-liveness states") {
        val cases = listOf(
            MultiAgentStatus.Running to "\"running\"",
            MultiAgentStatus.Idle to "\"idle\"",
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

    test("wait_agent observes its injected pending steer") {
        val tool = waitAgentTool(
            MutableStateFlow(
                listOf<ResponseItem.Steerable>(
                    ResponseItem.Message(
                        role = MessageRole.User,
                        content = listOf(ContentItem.InputText("agent update")),
                    ),
                ),
            ),
        )

        val output = tool.handle(
            ResponseItem.FunctionCall(
                name = MultiAgentTools.WaitAgentName,
                arguments = "{}",
                callId = "wait_1",
            ),
        ).first as ResponseItem.FunctionCallOutput
        val body = output.output.body as FunctionCallOutputBody.Text

        assertEquals(
            WaitAgentResult(message = "Wait completed.", timedOut = false),
            ToolBuilderJson.decodeFromString(WaitAgentResult.serializer(), body.text),
        )
    }

    test("spawn fork mode is decoded with the tool input") {
        val cases = listOf(
            "none" to SpawnForkMode.None,
            "all" to SpawnForkMode.All,
            "3" to SpawnForkMode.Recent(3),
        )

        cases.forEach { (wireValue, expected) ->
            assertEquals(
                expected,
                OpenAiJsonCodec.decodeFromString(
                    SpawnAgentArgs.serializer(),
                    "{\"task_name\":\"worker\",\"message\":\"Work\",\"fork_turns\":\"$wireValue\"}",
                ).forkTurns,
            )
            assertEquals(
                "\"$wireValue\"",
                OpenAiJsonCodec.encodeToString(SpawnForkModeSerializer, expected),
            )
        }

        assertEquals(
            SpawnForkMode.All,
            OpenAiJsonCodec.decodeFromString(
                SpawnAgentArgs.serializer(),
                "{\"task_name\":\"worker\",\"message\":\"Work\"}",
            ).forkTurns,
        )
        assertEquals(
            SpawnForkMode.All,
            OpenAiJsonCodec.decodeFromString(
                SpawnAgentArgs.serializer(),
                "{\"task_name\":\"worker\",\"message\":\"Work\",\"fork_turns\":null}",
            ).forkTurns,
        )
        assertEquals(
            SpawnForkMode.All,
            OpenAiJsonCodec.decodeFromString(
                SpawnAgentArgs.serializer(),
                "{\"task_name\":\"worker\",\"message\":\"Work\",\"fork_turns\":\" ALL \"}",
            ).forkTurns,
        )

        listOf("\"0\"", "\"-1\"", "\"recent\"", "3").forEach { invalidValue ->
            assertFailsWith<SerializationException> {
                OpenAiJsonCodec.decodeFromString(
                    SpawnAgentArgs.serializer(),
                    "{\"task_name\":\"worker\",\"message\":\"Work\",\"fork_turns\":$invalidValue}",
                )
            }
        }
    }

}

private fun ObjectPropertyDefinition.jsonObject() = OpenAiJsonCodec.parseToJsonElement(
    OpenAiJsonCodec.encodeToString(this),
).jsonObject
