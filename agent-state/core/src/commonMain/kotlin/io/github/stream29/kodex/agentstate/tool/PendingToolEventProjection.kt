package io.github.stream29.kodex.agentstate.tool

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
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventTools
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaTools
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.utils.applypatch.parsePatch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement

/** Converts one provider-issued local tool call at the AgentState boundary. */
public actual fun ResponseItem.ToolCall.toPendingToolEvent(): PendingToolEvent =
    when (this) {
        is ResponseItem.FunctionCall -> toPendingToolEvent()
        is ResponseItem.CustomToolCall -> toPendingToolEvent()
        is ResponseItem.ClientToolSearchCall -> toPendingToolEvent()
    }

private fun ResponseItem.FunctionCall.toPendingToolEvent(): PendingToolEvent {
    fun <Arguments> typed(
        deserializer: DeserializationStrategy<Arguments>,
        transform: (Arguments) -> PendingToolEvent,
    ): PendingToolEvent =
        decodeArguments(deserializer)?.let(transform)
            ?: invalid("Invalid JSON arguments for ${qualifiedName()}.")

    return when {
        namespace == null && name == UnifiedExecTools.ExecCommandName ->
            typed(ExecCommandArguments.serializer()) { arguments ->
                PendingCommandExecutionToolEvent(
                    callId = callId,
                    itemId = id,
                    action = PendingCommandExecutionAction.ExecCommand(arguments),
                )
            }

        namespace == null && name == AwaitEventTools.Name ->
            typed(AwaitEventArguments.serializer()) { arguments ->
                PendingAwaitEventToolEvent(
                    callId = callId,
                    itemId = id,
                    arguments = arguments,
                )
            }

        namespace == null && name == ReadMediaTools.Name ->
            typed(ReadMediaToolArguments.serializer()) { arguments ->
                PendingReadMediaToolEvent(
                    callId = callId,
                    itemId = id,
                    arguments = arguments,
                )
            }

        namespace == null && name == "update_plan" ->
            typed(UpdatePlanArgs.serializer()) { arguments ->
                PendingPlanUpdate(
                    callId = callId,
                    itemId = id,
                    arguments = arguments,
                )
            }

        else ->
            parsedJson()?.let { arguments ->
                PendingFunctionToolEvent(
                    callId = callId,
                    itemId = id,
                    name = name,
                    namespace = namespace,
                    arguments = arguments,
                )
            } ?: invalid("Invalid JSON arguments for ${qualifiedName()}.")
    }
}

private fun ResponseItem.CustomToolCall.toPendingToolEvent(): PendingToolEvent =
    if (namespace == null && name == ApplyPatchTools.Name) {
        try {
            PendingPatchToolEvent(
                callId = callId,
                itemId = id,
                diff = input.parsePatch(),
            )
        } catch (failure: IllegalArgumentException) {
            PendingInvalidToolCall(
                callId = callId,
                itemId = id,
                invocation = PendingInvalidToolInvocation.Custom(
                    name = name,
                    namespace = namespace,
                    input = input,
                ),
                message = failure.message ?: "Invalid apply_patch input.",
            )
        }
    } else {
        PendingCustomToolEvent(
            callId = callId,
            itemId = id,
            name = name,
            namespace = namespace,
            input = input,
        )
    }

private fun ResponseItem.ClientToolSearchCall.toPendingToolEvent(): PendingToolEvent =
    PendingInvalidToolCall(
        callId = callId,
        itemId = id,
        invocation = PendingInvalidToolInvocation.Function(
            name = "tool_search",
            namespace = null,
            arguments = OpenAiJsonCodec.encodeToString(JsonElement.serializer(), arguments),
        ),
        message = "Client tool_search is no longer supported.",
    )

private fun ResponseItem.FunctionCall.invalid(message: String): PendingInvalidToolCall =
    PendingInvalidToolCall(
        callId = callId,
        itemId = id,
        invocation = PendingInvalidToolInvocation.Function(
            name = name,
            namespace = namespace,
            arguments = arguments,
        ),
        message = message,
    )

private fun ResponseItem.FunctionCall.qualifiedName(): String =
    namespace?.let { "$it.$name" } ?: name

private fun ResponseItem.FunctionCall.parsedJson(): JsonElement? =
    try {
        OpenAiJsonCodec.parseToJsonElement(arguments)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun <Arguments> ResponseItem.FunctionCall.decodeArguments(
    deserializer: DeserializationStrategy<Arguments>,
): Arguments? =
    try {
        OpenAiJsonCodec.decodeFromString(deserializer, arguments)
    } catch (_: IllegalArgumentException) {
        null
    }
