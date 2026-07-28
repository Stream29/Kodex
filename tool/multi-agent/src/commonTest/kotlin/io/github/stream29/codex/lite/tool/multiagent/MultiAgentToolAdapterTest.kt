package io.github.stream29.codex.lite.tool.multiagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ServiceTier
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentResult
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsResult
import io.github.stream29.codex.lite.tool.multiagent.ListedAgent
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentStatus
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentResult
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentResult
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertIs

val multiAgentToolAdapterTest by testSuite {
    test("tools decode typed arguments and encode protocol outputs") {
        val client = RecordingMultiAgentToolClient()
        val tools = createMultiAgentTools(client).associateBy { tool ->
            assertIs<ResponsesApiTool>(tool.spec).name
        }
        val spawnArgs = SpawnAgentArgs(
            taskName = "worker",
            message = "Inspect storage.",
            forkTurns = "3",
            model = OpenAiModelId("gpt-test"),
            reasoningEffort = ReasoningEffort.High,
            serviceTier = ServiceTier.Fast,
        )

        val spawnOutput = tools.invoke(MultiAgentTools.SpawnAgentName, spawnArgs)
        assertEquals(spawnArgs, client.spawnArgs)
        assertEquals(
            "{\"task_name\":\"/root/worker\",\"nickname\":null}",
            spawnOutput.text,
        )

        val sendArgs = SendMessageArgs("/root/worker", "status?")
        assertEquals("", tools.invoke(MultiAgentTools.SendMessageName, sendArgs).text)
        assertEquals(sendArgs, client.sendArgs)

        val followupArgs = FollowupTaskArgs("/root/worker", "Continue.")
        assertEquals("", tools.invoke(MultiAgentTools.FollowupTaskName, followupArgs).text)
        assertEquals(followupArgs, client.followupArgs)

        val waitArgs = WaitAgentArgs(timeoutMs = 10_000)
        assertEquals(
            "{\"message\":\"Wait completed.\",\"timed_out\":false}",
            tools.invoke(MultiAgentTools.WaitAgentName, waitArgs).text,
        )
        assertEquals(waitArgs, client.waitArgs)

        val interruptArgs = InterruptAgentArgs("/root/worker")
        assertEquals(
            "{\"previous_status\":\"running\"}",
            tools.invoke(MultiAgentTools.InterruptAgentName, interruptArgs).text,
        )
        assertEquals(interruptArgs, client.interruptArgs)

        val listArgs = ListAgentsArgs()
        assertEquals(
            "{\"agents\":[{\"agent_name\":\"/root/worker\",\"agent_status\":{\"completed\":null},\"last_task_message\":null}]}",
            tools.invoke(MultiAgentTools.ListAgentsName, listArgs).text,
        )
        assertEquals(listArgs, client.listArgs)
    }

    test("client failures become unsuccessful tool outputs") {
        val client = RecordingMultiAgentToolClient(failure = "target is unavailable")
        val tool = createMultiAgentTools(client).single { candidate ->
            assertIs<ResponsesApiTool>(candidate.spec).name == MultiAgentTools.SendMessageName
        }
        val output = assertIs<ResponseItem.FunctionCallOutput>(
            tool.handle(
                ResponseItem.FunctionCall(
                    name = MultiAgentTools.SendMessageName,
                    arguments = "{\"target\":\"/root/missing\",\"message\":\"hello\"}",
                    callId = "call_send",
                ),
            ),
        )

        assertEquals(false, output.output.success)
        assertEquals("target is unavailable", assertIs<FunctionCallOutputBody.Text>(output.output.body).text)
    }
}

private suspend inline fun <reified Args> Map<String, Tool>.invoke(
    name: String,
    args: Args,
): ToolTextOutput {
    val output = assertIs<ResponseItem.FunctionCallOutput>(
        getValue(name).handle(
            ResponseItem.FunctionCall(
                name = name,
                arguments = OpenAiJsonCodec.encodeToString(args),
                callId = "call_$name",
            ),
        ),
    )
    assertEquals(true, output.output.success)
    return ToolTextOutput(assertIs<FunctionCallOutputBody.Text>(output.output.body).text)
}

private data class ToolTextOutput(val text: String)

private class RecordingMultiAgentToolClient(
    /** Nullable only when operations should succeed; non-null text forces every operation to fail. */
    private val failure: String? = null,
) : MultiAgentToolClient {
    lateinit var spawnArgs: SpawnAgentArgs
    lateinit var sendArgs: SendMessageArgs
    lateinit var followupArgs: FollowupTaskArgs
    lateinit var waitArgs: WaitAgentArgs
    lateinit var interruptArgs: InterruptAgentArgs
    lateinit var listArgs: ListAgentsArgs

    override suspend fun spawnAgent(args: SpawnAgentArgs): MultiAgentToolResult<SpawnAgentResult> {
        spawnArgs = args
        return result(SpawnAgentResult("/root/worker"))
    }

    override suspend fun sendMessage(args: SendMessageArgs): MultiAgentToolResult<Unit> {
        sendArgs = args
        return result(Unit)
    }

    override suspend fun followupTask(args: FollowupTaskArgs): MultiAgentToolResult<Unit> {
        followupArgs = args
        return result(Unit)
    }

    override suspend fun waitAgent(args: WaitAgentArgs): MultiAgentToolResult<WaitAgentResult> {
        waitArgs = args
        return result(WaitAgentResult("Wait completed.", timedOut = false))
    }

    override suspend fun interruptAgent(
        args: InterruptAgentArgs,
    ): MultiAgentToolResult<InterruptAgentResult> {
        interruptArgs = args
        return result(InterruptAgentResult(MultiAgentStatus.Running))
    }

    override suspend fun listAgents(args: ListAgentsArgs): MultiAgentToolResult<ListAgentsResult> {
        listArgs = args
        return result(
            ListAgentsResult(
                listOf(
                    ListedAgent(
                        agentName = "/root/worker",
                        agentStatus = MultiAgentStatus.Completed(null),
                    ),
                ),
            ),
        )
    }

    private fun <Value> result(value: Value): MultiAgentToolResult<Value> =
        if (failure == null) {
            MultiAgentToolResult.Success(value)
        } else {
            MultiAgentToolResult.Failure(failure)
        }
}
