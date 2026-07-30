package io.github.stream29.codex.lite.agentruntime.decorator.tool

import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableAgentDeliveryResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableImageGenerationResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableImageGenerationToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableImageViewResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableInterruptAgentResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableListAgentsResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMultiAgentOperation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMultiAgentToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableSpawnAgentResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableWaitAgentResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableWebSearchResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.hook.toolutils.runPostToolUse
import io.github.stream29.codex.lite.hook.toolutils.runPreToolUse
import io.github.stream29.codex.lite.openai.CallToolResult
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolName
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Executes borrowed fixed and dynamic tools plus client tool-search calls.
 *
 * Tool construction, dynamic catalog updates, and resource ownership remain
 * outside this decorator. It only samples the supplied clean pending events,
 * routes them, runs hooks, and persists their clean completions.
 */
public class CodexToolRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    fixedTools: List<Tool>,
    private val dynamicTools: StateFlow<List<Tool>>,
    private val toolSearch: StateFlow<ToolSearchEngine>,
    private val toolHooks: ToolHooks,
) : ResumableAgentLayer by delegate {
    private val fixedToolsByName: Map<ToolName, Tool> = fixedTools.toToolMap()

    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        while (true) {
            var pending = state.value as? CodexAgentStateValue.ToolPending
            if (pending == null) {
                delegate.resume().collect { send(it) }
                pending = state.value as? CodexAgentStateValue.ToolPending
                    ?: return@channelFlow
            }
            val toolsByName = fixedToolsByName.merge(dynamicTools.value.toToolMap())
            var handledEvent = false
            for (pendingEvent in pending.events) {
                when (pendingEvent) {
                    is PendingInvalidToolCall -> {
                        completeToolCall(pendingEvent.completedEvent())
                        handledEvent = true
                    }

                    is PendingToolSearchEvent -> {
                        completeToolCall(toolSearch.value.handle(pendingEvent))
                        handledEvent = true
                    }

                    else -> {
                        val toolName = pendingEvent.requireToolName()
                        val tool = toolsByName[toolName]
                        if (tool == null && toolName.namespace?.startsWith("mcp__") == true) {
                            completeToolCall(
                                pendingEvent.failedEvent(
                                    "The MCP tool is no longer available in the current catalog.",
                                ),
                            )
                        } else if (tool == null) {
                            continue
                        } else {
                            handleToolCall(tool, pendingEvent)
                        }
                        handledEvent = true
                    }
                }
            }
            if (!handledEvent) {
                return@channelFlow
            }
            if (state.value is CodexAgentStateValue.ToolPending) {
                return@channelFlow
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun handleToolCall(
        tool: Tool,
        pending: PendingToolEvent,
    ) {
        when (val result = toolHooks.runPreToolUse(delegate.storage, pending)) {
            is PreToolUseResult.Block -> {
                completeToolCall(pending.failedEvent(result.reason))
                return
            }

            PreToolUseResult.Continue -> Unit
        }
        val completed = tool.handle(pending)
        toolHooks.runPostToolUse(
            storage = delegate.storage,
            completed = completed,
        )
        // State-bound tools may atomically persist their own specialized output.
        val remainsPending = (state.value as? CodexAgentStateValue.ToolPending)
            ?.events
            ?.any { event -> event.callId == pending.callId }
            ?: false
        if (remainsPending) {
            completeToolCall(completed)
        }
    }
}

private fun PendingInvalidToolCall.completedEvent(): StableCleanEvent.InvalidToolCall =
    StableCleanEvent.InvalidToolCall(
        callId = callId,
        itemId = itemId,
        invocation = invocation.toStableInvocation(),
        message = message,
    )

private fun PendingInvalidToolInvocation.toStableInvocation(): InvalidToolInvocation =
    when (this) {
        is PendingInvalidToolInvocation.Function ->
            InvalidToolInvocation.Function(
                name = name,
                namespace = namespace,
                arguments = arguments,
            )

        is PendingInvalidToolInvocation.Custom ->
            InvalidToolInvocation.Custom(
                name = name,
                namespace = namespace,
                input = input,
            )

        is PendingInvalidToolInvocation.ToolSearch ->
            InvalidToolInvocation.ToolSearch(arguments)
    }

/**
 * Adds a tool-execution layer over externally owned tool state.
 *
 * This runtime neither creates nor closes any supplied tool or state flow.
 */
public fun ResumableAgentLayer.toolRuntime(
    fixedTools: List<Tool>,
    dynamicTools: StateFlow<List<Tool>>,
    toolSearch: StateFlow<ToolSearchEngine>,
    toolHooks: ToolHooks,
): CodexToolRuntime =
    CodexToolRuntime(
        delegate = this,
        fixedTools = fixedTools,
        dynamicTools = dynamicTools,
        toolSearch = toolSearch,
        toolHooks = toolHooks,
    )

private fun List<Tool>.toToolMap(): Map<ToolName, Tool> {
    val routes = flatMap { tool ->
        tool.routingNames().map { toolName -> toolName to tool }
    }
    val duplicateNames = routes
        .groupBy(keySelector = { (toolName) -> toolName })
        .filterValues { routesForName -> routesForName.size > 1 }
        .keys
    require(duplicateNames.isEmpty()) {
        "Multiple tools handle the same name: ${duplicateNames.joinToString()}"
    }
    return routes.toMap()
}

private fun Map<ToolName, Tool>.merge(dynamic: Map<ToolName, Tool>): Map<ToolName, Tool> {
    val duplicateNames = keys intersect dynamic.keys
    require(duplicateNames.isEmpty()) {
        "Fixed and dynamic tools handle the same name: ${duplicateNames.joinToString()}"
    }
    return this + dynamic
}

private fun Tool.routingNames(): List<ToolName> {
    val names = when (val spec = spec) {
        is ResponsesApiTool -> listOf(ToolName.plain(spec.name))
        is FreeformTool -> listOf(ToolName.plain(spec.name))
        is ResponsesApiNamespace -> spec.tools.map { namespaceTool ->
            when (namespaceTool) {
                is ResponsesApiTool -> ToolName.namespaced(spec.name, namespaceTool.name)
            }
        }

        is ToolSpec.ImageGeneration,
        is ToolSpec.ToolSearch,
        is ToolSpec.WebSearch -> error("CodexToolRuntime only accepts callable local tool specs.")
    }
    require(names.isNotEmpty()) { "A local Tool must expose at least one callable name." }
    return names
}

private fun PendingToolEvent.requireToolName(): ToolName =
    ToolName(
        name = requireNotNull(toolName) {
            "Pending event ${this::class.simpleName} does not identify a local tool route."
        },
        namespace = toolNamespace,
    )

private fun ToolSearchEngine.handle(
    pending: PendingToolSearchEvent,
): StableToolSearchEvent =
    StableToolSearchEvent(
        callId = pending.callId,
        itemId = pending.itemId,
        arguments = pending.arguments,
        result = search(pending.arguments),
    )

private fun PendingToolEvent.failedEvent(message: String): StableCleanEvent.CompletedTool =
    when (this) {
        is PendingFunctionToolEvent ->
            StableTextToolEvent(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                arguments = arguments,
                result = message,
                success = false,
            )

        is PendingCustomToolEvent ->
            StableCustomToolEvent(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                input = input,
                result = FunctionCallOutputPayload.fromText(message),
                success = false,
            )

        is PendingMcpToolEvent ->
            StableMcpToolEvent(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                arguments = arguments,
                result = mcpFailureResult(message),
            )

        is PendingPatchToolEvent ->
            StablePatchToolEvent(
                callId = callId,
                itemId = itemId,
                diff = diff,
                result = StablePatchToolExecutionResult.Failure(message),
            )

        is PendingCommandExecutionToolEvent ->
            StableCommandExecutionToolEvent(
                callId = callId,
                itemId = itemId,
                action = action.toStableAction(),
                result = StableCommandExecutionResult.Failure(message),
            )

        is PendingWebSearchToolEvent ->
            StableWebSearchToolEvent(
                callId = callId,
                itemId = itemId,
                commands = commands,
                result = StableWebSearchResult.Failure(message),
            )

        is PendingImageGenerationToolEvent ->
            StableImageGenerationToolEvent(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableImageGenerationResult.Failure(message),
            )

        is PendingImageViewToolEvent ->
            StableImageViewToolEvent(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableImageViewResult.Failure(message),
            )

        is PendingMultiAgentToolEvent ->
            StableMultiAgentToolEvent(
                callId = callId,
                itemId = itemId,
                operation = operation.toFailedStableOperation(message),
            )

        is PendingRequestUserInputToolEvent ->
            StableRequestUserInputToolEvent(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableRequestUserInputResult.Failure(message),
            )

        is PendingPlanUpdate ->
            StableTextToolEvent(
                callId = callId,
                itemId = itemId,
                name = requireNotNull(toolName),
                arguments = OpenAiJsonCodec.encodeToJsonElement(
                    UpdatePlanArgs.serializer(),
                    arguments,
                ),
                result = message,
                success = false,
            )

        is PendingInvalidToolCall,
        is PendingToolSearchEvent,
        -> error("$this cannot be blocked by a tool hook.")
    }

private fun PendingCommandExecutionAction.toStableAction(): StableCommandExecutionAction =
    when (this) {
        is PendingCommandExecutionAction.ExecCommand ->
            StableCommandExecutionAction.ExecCommand(arguments)

        is PendingCommandExecutionAction.WriteStdin ->
            StableCommandExecutionAction.WriteStdin(arguments)
    }

private fun PendingMultiAgentInvocation.toFailedStableOperation(
    message: String,
): StableMultiAgentOperation =
    when (this) {
        is PendingMultiAgentInvocation.SpawnAgent ->
            StableMultiAgentOperation.SpawnAgent(
                arguments = arguments,
                result = StableSpawnAgentResult.Failure(message),
            )

        is PendingMultiAgentInvocation.SendMessage ->
            StableMultiAgentOperation.SendMessage(
                arguments = arguments,
                result = StableAgentDeliveryResult.Failure(message),
            )

        is PendingMultiAgentInvocation.FollowupTask ->
            StableMultiAgentOperation.FollowupTask(
                arguments = arguments,
                result = StableAgentDeliveryResult.Failure(message),
            )

        is PendingMultiAgentInvocation.WaitAgent ->
            StableMultiAgentOperation.WaitAgent(
                arguments = arguments,
                result = StableWaitAgentResult.Failure(message),
            )

        is PendingMultiAgentInvocation.InterruptAgent ->
            StableMultiAgentOperation.InterruptAgent(
                arguments = arguments,
                result = StableInterruptAgentResult.Failure(message),
            )

        is PendingMultiAgentInvocation.ListAgents ->
            StableMultiAgentOperation.ListAgents(
                arguments = arguments,
                result = StableListAgentsResult.Failure(message),
            )
    }

private fun mcpFailureResult(message: String): CallToolResult =
    CallToolResult(
        content = listOf(
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(message))
            },
        ),
        isError = true,
    )
