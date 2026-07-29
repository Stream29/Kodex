package io.github.stream29.codex.lite.agentstate.tool

import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionArguments
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingImageGenerationRequest
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingWebSearchRequest
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenNamespace
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolName
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchExecution
import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageTools
import io.github.stream29.codex.lite.tool.webrun.WebRunNamespace
import io.github.stream29.codex.lite.tool.webrun.WebRunToolName
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

/** Projects one raw local tool call into the unstable clean timeline. */
public fun ResponseItem.ToolCall.toPendingToolEvent(): PendingToolEvent =
    PendingToolEvent(
        callId = callId,
        invocation = when (this) {
            is ResponseItem.FunctionCall -> toPendingInvocation()
            is ResponseItem.CustomToolCall -> toPendingInvocation()
            is ResponseItem.ClientToolSearchCall -> toPendingInvocation()
        },
    )

private fun ResponseItem.FunctionCall.toPendingInvocation(): PendingToolInvocation {
    fun <T> specialized(
        deserializer: DeserializationStrategy<T>,
        transform: (T) -> PendingToolInvocation,
    ): PendingToolInvocation =
        decodeArguments(deserializer)?.let(transform) ?: genericInvocation()

    return when {
        namespace == null && name == UnifiedExecTools.ExecCommandName ->
            specialized(ExecCommandArguments.serializer()) { arguments ->
                PendingToolInvocation.CommandExecution(
                    PendingCommandExecutionAction.ExecCommand(arguments),
                )
            }

        namespace == null && name == UnifiedExecTools.WriteStdinName ->
            specialized(WriteStdinArguments.serializer()) { arguments ->
                PendingToolInvocation.CommandExecution(
                    PendingCommandExecutionAction.WriteStdin(arguments),
                )
            }

        namespace == WebRunNamespace && name == WebRunToolName ->
            specialized(SearchCommands.serializer()) { commands ->
                PendingToolInvocation.WebSearch(PendingWebSearchRequest.WebRun(commands))
            }

        namespace == ImageGenNamespace && name == ImageGenToolName ->
            specialized(ImageGenToolArguments.serializer()) { arguments ->
                PendingToolInvocation.ImageGeneration(
                    PendingImageGenerationRequest.Tool(arguments),
                )
            }

        namespace == null && name == ViewImageTools.Name ->
            specialized(ViewImageToolArguments.serializer()) { arguments ->
                PendingToolInvocation.ImageView(arguments)
            }

        namespace == null && name == MultiAgentTools.SpawnAgentName ->
            specialized(SpawnAgentArgs.serializer()) { arguments ->
                PendingToolInvocation.MultiAgent(
                    PendingMultiAgentInvocation.SpawnAgent(arguments),
                )
            }

        namespace == null && name == MultiAgentTools.SendMessageName ->
            specialized(SendMessageArgs.serializer()) { arguments ->
                PendingToolInvocation.MultiAgent(
                    PendingMultiAgentInvocation.SendMessage(arguments),
                )
            }

        namespace == null && name == MultiAgentTools.FollowupTaskName ->
            specialized(FollowupTaskArgs.serializer()) { arguments ->
                PendingToolInvocation.MultiAgent(
                    PendingMultiAgentInvocation.FollowupTask(arguments),
                )
            }

        namespace == null && name == MultiAgentTools.WaitAgentName ->
            specialized(WaitAgentArgs.serializer()) { arguments ->
                PendingToolInvocation.MultiAgent(
                    PendingMultiAgentInvocation.WaitAgent(arguments),
                )
            }

        namespace == null && name == MultiAgentTools.InterruptAgentName ->
            specialized(InterruptAgentArgs.serializer()) { arguments ->
                PendingToolInvocation.MultiAgent(
                    PendingMultiAgentInvocation.InterruptAgent(arguments),
                )
            }

        namespace == null && name == MultiAgentTools.ListAgentsName ->
            specialized(ListAgentsArgs.serializer()) { arguments ->
                PendingToolInvocation.MultiAgent(
                    PendingMultiAgentInvocation.ListAgents(arguments),
                )
            }

        namespace == null && name == RequestUserInputTools.Name ->
            specialized(RequestUserInputArgs.serializer()) { arguments ->
                PendingToolInvocation.RequestUserInput(arguments)
            }

        else -> genericInvocation()
    }
}

private fun ResponseItem.CustomToolCall.toPendingInvocation(): PendingToolInvocation =
    if (namespace == null && name == ApplyPatchTools.Name) {
        try {
            PendingToolInvocation.ApplyPatch(input.parsePatch())
        } catch (_: IllegalArgumentException) {
            genericInvocation()
        }
    } else {
        genericInvocation()
    }

private fun ResponseItem.ClientToolSearchCall.toPendingInvocation(): PendingToolInvocation =
    try {
        PendingToolInvocation.ToolSearch(
            execution = ToolSearchExecution.Client,
            arguments = OpenAiJsonCodec.decodeFromJsonElement(
                SearchToolCallParams.serializer(),
                arguments,
            ),
        )
    } catch (_: SerializationException) {
        PendingToolInvocation.Function(
            name = "tool_search",
            arguments = PendingFunctionArguments.Json(arguments),
        )
    } catch (_: IllegalArgumentException) {
        PendingToolInvocation.Function(
            name = "tool_search",
            arguments = PendingFunctionArguments.Json(arguments),
        )
    }

private fun ResponseItem.FunctionCall.genericInvocation(): PendingToolInvocation.Function =
    PendingToolInvocation.Function(
        name = name,
        namespace = namespace,
        arguments = arguments.toPendingArguments(),
    )

private fun ResponseItem.CustomToolCall.genericInvocation(): PendingToolInvocation.Custom =
    PendingToolInvocation.Custom(
        name = name,
        namespace = namespace,
        input = input,
    )

private fun <T> ResponseItem.FunctionCall.decodeArguments(
    deserializer: DeserializationStrategy<T>,
): T? =
    try {
        OpenAiJsonCodec.decodeFromString(deserializer, arguments)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun String.toPendingArguments(): PendingFunctionArguments =
    try {
        PendingFunctionArguments.Json(OpenAiJsonCodec.parseToJsonElement(this))
    } catch (_: SerializationException) {
        PendingFunctionArguments.InvalidJson(this)
    } catch (_: IllegalArgumentException) {
        PendingFunctionArguments.InvalidJson(this)
    }
