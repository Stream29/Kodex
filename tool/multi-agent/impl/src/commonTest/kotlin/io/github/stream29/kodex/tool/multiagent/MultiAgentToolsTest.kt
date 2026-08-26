package io.github.stream29.kodex.tool.multiagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentOperation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWaitAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.builder.ToolBuilderJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    test("spawn precompaction requires a changed known comp hash and payload") {
        assertTrue(requiresSpawnPrecompaction(true, "3000", "2911"))
        assertFalse(requiresSpawnPrecompaction(false, "3000", "2911"))
        assertFalse(requiresSpawnPrecompaction(true, "3000", "3000"))
        assertFalse(requiresSpawnPrecompaction(true, null, "2911"))
        assertFalse(requiresSpawnPrecompaction(true, "3000", null))
        assertFalse(requiresSpawnPrecompaction(true, "", "2911"))
        assertFalse(requiresSpawnPrecompaction(true, "3000", ""))
    }

    test("static tool descriptions match the Multi-agent V2 contract") {
        assertEquals(
            """
                Spawns an agent to work on the specified task. If your current task is `/root/task1` and you spawn_agent with task_name "task_3" the agent will have canonical task name `/root/task1/task_3`.
                The spawned agent will have the same tools as you and the ability to spawn its own subagents.
                Spawned agents inherit your current model by default. Omit `model` to use that preferred default; set `model` only when an explicit override is needed.
                Only call this tool for a concrete, bounded subtask that can run independently alongside useful local work; otherwise continue locally.
                It will be able to send you and other running agents messages, and its final answer will be provided to you when it finishes.
                The new agent's canonical task name will be provided to it along with the message.

                The spawned agent receives the current active context window. It does not receive completed history that has already been compacted away.
            """.trimIndent(),
            MultiAgentTools.spawnAgentSpec.description,
        )
        assertEquals(
            "Send a message to an existing agent. The message will be delivered promptly. " +
                "Does not trigger a new turn.",
            MultiAgentTools.sendMessageSpec.description,
        )
        assertEquals(
            "Send a follow-up task to an existing non-root target agent and trigger a turn if it " +
                "is idle. If the target is already running, deliver the task promptly at message " +
                "boundaries while sampling, or after the pending tool call completes.",
            MultiAgentTools.followupTaskSpec.description,
        )
        assertEquals(
            "Wait for a pending steering message from any live agent, including queued messages " +
                "and final-status notifications. Does not return final content; it returns an " +
                "activity or timeout summary.",
            MultiAgentTools.waitAgentSpec.description,
        )
        assertEquals(
            "Interrupt an agent's current turn, if any, and return its previous status. The agent " +
                "remains available for messages and follow-up tasks.",
            MultiAgentTools.interruptAgentSpec.description,
        )
        assertEquals(
            "List live agents in the current root thread tree. Optionally filter by task-path prefix.",
            MultiAgentTools.listAgentsSpec.description,
        )
        assertEquals(
            "Timeout in milliseconds. Defaults to 30000, min 10000, max 3600000.",
            MultiAgentTools.waitAgentSpec.parameters.jsonObject()
                .getValue("properties").jsonObject
                .getValue("timeout_ms").jsonObject
                .getValue("description").jsonPrimitive.content,
        )
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

    test("standard tool JSON explicitly encodes nullable defaults") {
        val spawn = ToolBuilderJson.encodeToString(
            SpawnAgentArgs.serializer(),
            SpawnAgentArgs(taskName = "worker", message = "Inspect the contract."),
        ).jsonObject()
        assertEquals(JsonNull, spawn["model"])
        assertEquals(JsonNull, spawn["reasoning_effort"])
        assertFalse("service_tier" in spawn)

        val result = ToolBuilderJson.encodeToString(
            SpawnAgentResult.serializer(),
            SpawnAgentResult(taskName = "/root/worker"),
        ).jsonObject()
        assertEquals(JsonNull, result["nickname"])

        val wait = ToolBuilderJson.encodeToString(
            WaitAgentArgs.serializer(),
            WaitAgentArgs(),
        ).jsonObject()
        assertEquals(JsonNull, wait["timeout_ms"])

        val list = ToolBuilderJson.encodeToString(
            ListAgentsArgs.serializer(),
            ListAgentsArgs(),
        ).jsonObject()
        assertEquals(JsonNull, list["path_prefix"])
    }

    test("wait_agent observes its injected pending steer") {
        val tool = waitAgentTool(
            MutableStateFlow(
                listOf<StableCleanEvent.Steerable>(
                    StableCleanEvent.UserMessage(
                        content = listOf(ContentItem.InputText("agent update")),
                    ),
                ),
            ),
        )

        val completed = assertIs<StableMultiAgentToolEvent>(
            tool.handle(
                PendingMultiAgentToolEvent(
                    callId = "wait_1",
                    operation = PendingMultiAgentInvocation.WaitAgent(WaitAgentArgs()),
                ),
            ),
        )
        val result = assertIs<StableMultiAgentOperation.WaitAgent>(completed.operation).result

        assertEquals(
            StableWaitAgentResult.Success(
                WaitAgentResult(message = "Wait completed.", timedOut = false),
            ),
            result,
        )
    }

    test("spawn ignores legacy fork and service-tier fields") {
        val args = OpenAiJsonCodec.decodeFromString(
            SpawnAgentArgs.serializer(),
            """{"task_name":"worker","message":"Work","fork_turns":"3","service_tier":"priority"}""",
        )

        assertEquals("worker", args.taskName)
        assertEquals("Work", args.message)
        assertEquals(null, args.model)
        assertEquals(null, args.reasoningEffort)
    }

}

private fun ObjectPropertyDefinition.jsonObject() = OpenAiJsonCodec.parseToJsonElement(
    OpenAiJsonCodec.encodeToString(this),
).jsonObject

private fun String.jsonObject() = ToolBuilderJson.parseToJsonElement(this).jsonObject
