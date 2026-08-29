package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Converts one pending local call into its matching durable failed tool event. */
public fun PendingToolEvent.toFailedToolEvent(message: String): StableCleanEvent.CompletedTool =
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
                name = toolName,
                arguments = StableToolJson.encodeToJsonElement(
                    UpdatePlanArgs.serializer(),
                    arguments,
                ),
                result = message,
                success = false,
            )

        is PendingInvalidToolCall ->
            StableInvalidToolCall(
                callId = callId,
                itemId = itemId,
                invocation = invocation.toStableInvocation(),
                message = message,
            )

        is PendingToolSearchEvent ->
            StableToolSearchEvent(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = ToolSearchResult.InvalidArguments(message),
            )
    }

private fun PendingCommandExecutionAction.toStableAction(): StableCommandExecutionAction =
    when (this) {
        is PendingCommandExecutionAction.ExecCommand ->
            StableCommandExecutionAction.ExecCommand(arguments)

        is PendingCommandExecutionAction.WriteStdin ->
            StableCommandExecutionAction.WriteStdin(arguments)
    }

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
