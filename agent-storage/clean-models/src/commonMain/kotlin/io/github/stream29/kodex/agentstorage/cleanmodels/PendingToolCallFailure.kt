package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolJson
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.UpdatePlanArgs

/** Converts one pending local call into its matching durable failed tool event. */
public actual fun PendingToolCall.toFailedToolEvent(message: String): AgentCommit.CompletedTool =
    when (this) {
        is PendingFunctionToolCall ->
            StableTextTool(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                arguments = arguments,
                result = message,
                success = false,
            )

        is PendingCustomToolCall ->
            StableCustomTool(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                input = input,
                result = FunctionCallOutputPayload.fromText(message),
                success = false,
            )

        is PendingPatch ->
            StablePatch(
                callId = callId,
                itemId = itemId,
                diff = diff,
                result = StablePatchToolExecutionResult.Failure(message),
            )

        is PendingCommandExecution ->
            StableCommandExecution(
                callId = callId,
                itemId = itemId,
                action = action.toStableAction(),
                result = StableCommandExecutionResult.Failure(message),
            )

        is PendingAwait ->
            StableAwait(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableAwaitEventResult.Failure(message),
            )

        is PendingReadMedia ->
            StableReadMedia(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableReadMediaResult.Failure(message),
            )

        is PendingPlanUpdate ->
            StableTextTool(
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
    }
