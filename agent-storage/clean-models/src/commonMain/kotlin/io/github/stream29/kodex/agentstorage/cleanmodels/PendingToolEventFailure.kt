package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolJson
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.UpdatePlanArgs

/** Converts one pending local call into its matching durable failed tool event. */
public actual fun PendingToolEvent.toFailedToolEvent(message: String): StableCleanEvent.CompletedTool =
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

        is PendingAwaitEventToolEvent ->
            StableAwaitEventToolEvent(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableAwaitEventResult.Failure(message),
            )

        is PendingReadMediaToolEvent ->
            StableReadMediaToolEvent(
                callId = callId,
                itemId = itemId,
                arguments = arguments,
                result = StableReadMediaResult.Failure(message),
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
