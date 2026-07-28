package io.github.stream29.codex.lite.tool.multiagent

import io.github.stream29.codex.lite.tool.builder.JsonToolHandlerResult
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import io.github.stream29.codex.lite.tool.builder.jsonTool
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.builder.textTool
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentResult
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsResult
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentResult
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentResult
import kotlinx.serialization.json.Json

internal sealed interface MultiAgentToolResult<out Value> {
    data class Success<out Value>(val value: Value) : MultiAgentToolResult<Value>
    data class Failure(val message: String) : MultiAgentToolResult<Nothing>
}

internal interface MultiAgentToolClient {
    suspend fun spawnAgent(args: SpawnAgentArgs): MultiAgentToolResult<SpawnAgentResult>
    suspend fun sendMessage(args: SendMessageArgs): MultiAgentToolResult<Unit>
    suspend fun followupTask(args: FollowupTaskArgs): MultiAgentToolResult<Unit>
    suspend fun waitAgent(args: WaitAgentArgs): MultiAgentToolResult<WaitAgentResult>
    suspend fun interruptAgent(args: InterruptAgentArgs): MultiAgentToolResult<InterruptAgentResult>
    suspend fun listAgents(args: ListAgentsArgs): MultiAgentToolResult<ListAgentsResult>
}

internal fun createMultiAgentTools(client: MultiAgentToolClient): List<Tool> = listOf(
    jsonTool(
        spec = MultiAgentTools.spawnAgentSpec,
        inputDeserializer = SpawnAgentArgs.serializer(),
        outputSerializer = SpawnAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args -> client.spawnAgent(args).toToolResult() },
    textTool(
        spec = MultiAgentTools.sendMessageSpec,
        inputDeserializer = SendMessageArgs.serializer(),
        json = MultiAgentToolJson,
    ) { args -> client.sendMessage(args).toTextToolResult() },
    textTool(
        spec = MultiAgentTools.followupTaskSpec,
        inputDeserializer = FollowupTaskArgs.serializer(),
        json = MultiAgentToolJson,
    ) { args -> client.followupTask(args).toTextToolResult() },
    jsonTool(
        spec = MultiAgentTools.waitAgentSpec,
        inputDeserializer = WaitAgentArgs.serializer(),
        outputSerializer = WaitAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args -> client.waitAgent(args).toToolResult() },
    jsonTool(
        spec = MultiAgentTools.interruptAgentSpec,
        inputDeserializer = InterruptAgentArgs.serializer(),
        outputSerializer = InterruptAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args -> client.interruptAgent(args).toToolResult() },
    jsonTool(
        spec = MultiAgentTools.listAgentsSpec,
        inputDeserializer = ListAgentsArgs.serializer(),
        outputSerializer = ListAgentsResult.serializer(),
        json = MultiAgentToolJson,
    ) { args -> client.listAgents(args).toToolResult() },
)

private val MultiAgentToolJson: Json = Json(ToolBuilderJson) {
    explicitNulls = true
}

private fun <Value> MultiAgentToolResult<Value>.toToolResult(): JsonToolHandlerResult<Value> =
    when (this) {
        is MultiAgentToolResult.Success -> jsonToolSuccess(value)
        is MultiAgentToolResult.Failure -> jsonToolFailure(message)
    }

private fun MultiAgentToolResult<Unit>.toTextToolResult(): JsonToolHandlerResult<String> =
    when (this) {
        is MultiAgentToolResult.Success -> jsonToolSuccess("")
        is MultiAgentToolResult.Failure -> jsonToolFailure(message)
    }
