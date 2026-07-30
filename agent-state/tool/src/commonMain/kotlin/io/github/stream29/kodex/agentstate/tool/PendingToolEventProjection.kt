package io.github.stream29.kodex.agentstate.tool

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.imagegeneration.ImageGenNamespace
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolName
import io.github.stream29.kodex.tool.multiagent.FollowupTaskArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentArgs
import io.github.stream29.kodex.tool.multiagent.ListAgentsArgs
import io.github.stream29.kodex.tool.multiagent.MultiAgentTools
import io.github.stream29.kodex.tool.multiagent.SendMessageArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentArgs
import io.github.stream29.kodex.tool.multiagent.WaitAgentArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageTools
import io.github.stream29.kodex.tool.webrun.WebRunNamespace
import io.github.stream29.kodex.tool.webrun.WebRunToolName
import io.github.stream29.kodex.utils.applypatch.parsePatch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/** Converts one provider-issued local tool call at the AgentState boundary. */
public fun ResponseItem.ToolCall.toPendingToolEvent(): PendingToolEvent =
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

        namespace == null && name == UnifiedExecTools.WriteStdinName ->
            typed(WriteStdinArguments.serializer()) { arguments ->
                PendingCommandExecutionToolEvent(
                    callId = callId,
                    itemId = id,
                    action = PendingCommandExecutionAction.WriteStdin(arguments),
                )
            }

        namespace == WebRunNamespace && name == WebRunToolName ->
            typed(SearchCommands.serializer()) { commands ->
                PendingWebSearchToolEvent(
                    callId = callId,
                    itemId = id,
                    commands = commands,
                )
            }

        namespace == ImageGenNamespace && name == ImageGenToolName ->
            typed(ImageGenToolArguments.serializer()) { arguments ->
                PendingImageGenerationToolEvent(
                    callId = callId,
                    itemId = id,
                    arguments = arguments,
                )
            }

        namespace == null && name == ViewImageTools.Name ->
            typed(ViewImageToolArguments.serializer()) { arguments ->
                PendingImageViewToolEvent(
                    callId = callId,
                    itemId = id,
                    arguments = arguments,
                )
            }

        namespace == null && name == MultiAgentTools.SpawnAgentName ->
            typed(SpawnAgentArgs.serializer()) { arguments ->
                multiAgent(PendingMultiAgentInvocation.SpawnAgent(arguments))
            }

        namespace == null && name == MultiAgentTools.SendMessageName ->
            typed(SendMessageArgs.serializer()) { arguments ->
                multiAgent(PendingMultiAgentInvocation.SendMessage(arguments))
            }

        namespace == null && name == MultiAgentTools.FollowupTaskName ->
            typed(FollowupTaskArgs.serializer()) { arguments ->
                multiAgent(PendingMultiAgentInvocation.FollowupTask(arguments))
            }

        namespace == null && name == MultiAgentTools.WaitAgentName ->
            typed(WaitAgentArgs.serializer()) { arguments ->
                multiAgent(PendingMultiAgentInvocation.WaitAgent(arguments))
            }

        namespace == null && name == MultiAgentTools.InterruptAgentName ->
            typed(InterruptAgentArgs.serializer()) { arguments ->
                multiAgent(PendingMultiAgentInvocation.InterruptAgent(arguments))
            }

        namespace == null && name == MultiAgentTools.ListAgentsName ->
            typed(ListAgentsArgs.serializer()) { arguments ->
                multiAgent(PendingMultiAgentInvocation.ListAgents(arguments))
            }

        namespace == null && name == RequestUserInputTools.Name ->
            typed(RequestUserInputArgs.serializer()) { arguments ->
                PendingRequestUserInputToolEvent(
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

        namespace?.startsWith("mcp__") == true ->
            parsedJson()?.let { arguments ->
                PendingMcpToolEvent(
                    callId = callId,
                    itemId = id,
                    name = name,
                    namespace = checkNotNull(namespace),
                    arguments = arguments,
                )
            } ?: invalid("Invalid JSON arguments for ${qualifiedName()}.")

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
    try {
        PendingToolSearchEvent(
            callId = callId,
            itemId = id,
            arguments = OpenAiJsonCodec.decodeFromJsonElement(
                SearchToolCallParams.serializer(),
                arguments,
            ),
        )
    } catch (_: SerializationException) {
        invalidToolSearch()
    } catch (_: IllegalArgumentException) {
        invalidToolSearch()
    }

private fun ResponseItem.FunctionCall.multiAgent(
    operation: PendingMultiAgentInvocation,
): PendingMultiAgentToolEvent =
    PendingMultiAgentToolEvent(
        callId = callId,
        itemId = id,
        operation = operation,
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

private fun ResponseItem.ClientToolSearchCall.invalidToolSearch(): PendingInvalidToolCall =
    PendingInvalidToolCall(
        callId = callId,
        itemId = id,
        invocation = PendingInvalidToolInvocation.ToolSearch(arguments),
        message = "Invalid client tool-search arguments.",
    )

private fun ResponseItem.FunctionCall.qualifiedName(): String =
    namespace?.let { "$it.$name" } ?: name

private fun ResponseItem.FunctionCall.parsedJson(): JsonElement? =
    try {
        OpenAiJsonCodec.parseToJsonElement(arguments)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun <Arguments> ResponseItem.FunctionCall.decodeArguments(
    deserializer: DeserializationStrategy<Arguments>,
): Arguments? =
    try {
        OpenAiJsonCodec.decodeFromString(deserializer, arguments)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
