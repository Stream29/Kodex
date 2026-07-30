package io.github.stream29.kodex.agentstate.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchQuery
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
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
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
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertIs

val pendingToolEventProjectionTest by testSuite {
    test("projects specialized function calls into direct pending branches") {
        val exec = ExecCommandArguments(command = "./gradlew check", workdir = "Kodex")
        val write = WriteStdinArguments(sessionId = 7, chars = "\u0003")
        val web = SearchCommands(searchQuery = listOf(SearchQuery("Kotlin serialization")))
        val imageGeneration = ImageGenToolArguments(prompt = "Draw the architecture.")
        val imageView = ViewImageToolArguments(path = "diagram.png")
        val requestUserInput = RequestUserInputArgs(
            questions = listOf(
                RequestUserInputQuestion(
                    id = "scope",
                    header = "Scope",
                    question = "Which scope?",
                ),
            ),
        )
        val cases: List<Pair<ResponseItem.FunctionCall, PendingToolEvent>> = listOf(
            functionCall(
                UnifiedExecTools.ExecCommandName,
                ExecCommandArguments.serializer(),
                exec,
            ) to PendingCommandExecutionToolEvent(
                callId = "call_${UnifiedExecTools.ExecCommandName}",
                itemId = ItemId,
                action = PendingCommandExecutionAction.ExecCommand(exec),
            ),
            functionCall(
                UnifiedExecTools.WriteStdinName,
                WriteStdinArguments.serializer(),
                write,
            ) to PendingCommandExecutionToolEvent(
                callId = "call_${UnifiedExecTools.WriteStdinName}",
                itemId = ItemId,
                action = PendingCommandExecutionAction.WriteStdin(write),
            ),
            functionCall(
                WebRunToolName,
                SearchCommands.serializer(),
                web,
                namespace = WebRunNamespace,
            ) to PendingWebSearchToolEvent(
                callId = "call_$WebRunToolName",
                itemId = ItemId,
                commands = web,
            ),
            functionCall(
                ImageGenToolName,
                ImageGenToolArguments.serializer(),
                imageGeneration,
                namespace = ImageGenNamespace,
            ) to PendingImageGenerationToolEvent(
                callId = "call_$ImageGenToolName",
                itemId = ItemId,
                arguments = imageGeneration,
            ),
            functionCall(
                ViewImageTools.Name,
                ViewImageToolArguments.serializer(),
                imageView,
            ) to PendingImageViewToolEvent(
                callId = "call_${ViewImageTools.Name}",
                itemId = ItemId,
                arguments = imageView,
            ),
            functionCall(
                RequestUserInputTools.Name,
                RequestUserInputArgs.serializer(),
                requestUserInput,
            ) to PendingRequestUserInputToolEvent(
                callId = "call_${RequestUserInputTools.Name}",
                itemId = ItemId,
                arguments = requestUserInput,
            ),
        )

        cases.forEach { (call, expected) ->
            assertEquals(expected, call.toPendingToolEvent())
            val projected = assertIs<ResponseItem.FunctionCall>(
                expected.toResponseHistoryItems().single(),
            )
            assertEquals(call.id, projected.id)
            assertEquals(call.callId, projected.callId)
            assertEquals(call.name, projected.name)
            assertEquals(call.namespace, projected.namespace)
            assertEquals(expected, projected.toPendingToolEvent())
        }
    }

    test("projects every multi-agent call with its typed operation") {
        val cases = listOf(
            multiAgentCase(
                MultiAgentTools.SpawnAgentName,
                SpawnAgentArgs.serializer(),
                SpawnAgentArgs("review", "Review this change."),
                PendingMultiAgentInvocation::SpawnAgent,
            ),
            multiAgentCase(
                MultiAgentTools.SendMessageName,
                SendMessageArgs.serializer(),
                SendMessageArgs("/root/review", "Focus on the model."),
                PendingMultiAgentInvocation::SendMessage,
            ),
            multiAgentCase(
                MultiAgentTools.FollowupTaskName,
                FollowupTaskArgs.serializer(),
                FollowupTaskArgs("/root/review", "Continue."),
                PendingMultiAgentInvocation::FollowupTask,
            ),
            multiAgentCase(
                MultiAgentTools.WaitAgentName,
                WaitAgentArgs.serializer(),
                WaitAgentArgs(timeoutMs = 30_000),
                PendingMultiAgentInvocation::WaitAgent,
            ),
            multiAgentCase(
                MultiAgentTools.InterruptAgentName,
                InterruptAgentArgs.serializer(),
                InterruptAgentArgs("/root/review"),
                PendingMultiAgentInvocation::InterruptAgent,
            ),
            multiAgentCase(
                MultiAgentTools.ListAgentsName,
                ListAgentsArgs.serializer(),
                ListAgentsArgs("/root"),
                PendingMultiAgentInvocation::ListAgents,
            ),
        )

        cases.forEach { (call, operation) ->
            assertEquals(
                PendingMultiAgentToolEvent(
                    callId = call.callId,
                    itemId = call.id,
                    operation = operation,
                ),
                call.toPendingToolEvent(),
            )
        }
    }

    test("projects client tool search and apply patch with call metadata") {
        val searchArguments = SearchToolCallParams("connected drive tools", limit = 3)
        val searchCall = ResponseItem.ClientToolSearchCall(
            id = ItemId,
            callId = "call_search",
            arguments = OpenAiJsonCodec.encodeToJsonElement(
                SearchToolCallParams.serializer(),
                searchArguments,
            ),
        )
        val patchText = """
            *** Begin Patch
            *** Delete File: obsolete.txt
            *** End Patch
        """.trimIndent()
        val patchCall = ResponseItem.CustomToolCall(
            id = ItemId,
            callId = "call_patch",
            name = ApplyPatchTools.Name,
            input = patchText,
        )

        assertEquals(
            PendingToolSearchEvent("call_search", ItemId, searchArguments),
            searchCall.toPendingToolEvent(),
        )
        assertEquals(
            PendingPatchToolEvent("call_patch", ItemId, patchText.parsePatch()),
            patchCall.toPendingToolEvent(),
        )
    }

    test("routes parse failures to pending invalid tool call") {
        val call = ResponseItem.FunctionCall(
            id = ItemId,
            callId = "call_invalid",
            name = UnifiedExecTools.ExecCommandName,
            arguments = """{"cmd":""",
        )

        val event = assertIs<PendingInvalidToolCall>(call.toPendingToolEvent())
        assertEquals(call.callId, event.callId)
        assertEquals(call.id, event.itemId)
        assertEquals(
            PendingInvalidToolInvocation.Function(
                name = call.name,
                arguments = call.arguments,
            ),
            event.invocation,
        )
        assertEquals(listOf(call), event.toResponseHistoryItems())
    }

    test("distinguishes dynamic function and MCP calls after JSON parsing") {
        val arguments = buildJsonObject { put("query", "design context") }
        val function = ResponseItem.FunctionCall(
            id = ItemId,
            callId = "call_dynamic",
            namespace = "google_drive",
            name = "search",
            arguments = arguments.toString(),
        )
        val mcp = function.copy(
            callId = "call_mcp",
            namespace = "mcp__google_drive",
        )

        assertEquals(
            PendingFunctionToolEvent(
                callId = function.callId,
                itemId = function.id,
                name = function.name,
                namespace = function.namespace,
                arguments = arguments,
            ),
            function.toPendingToolEvent(),
        )
        assertEquals(
            PendingMcpToolEvent(
                callId = mcp.callId,
                itemId = mcp.id,
                name = mcp.name,
                namespace = checkNotNull(mcp.namespace),
                arguments = arguments,
            ),
            mcp.toPendingToolEvent(),
        )
    }
}

private val ItemId: ResponseItemId = ResponseItemId("item_1")

private fun <Arguments> functionCall(
    name: String,
    serializer: SerializationStrategy<Arguments>,
    arguments: Arguments,
    namespace: String? = null,
): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(
        id = ItemId,
        callId = "call_$name",
        namespace = namespace,
        name = name,
        arguments = OpenAiJsonCodec.encodeToString(serializer, arguments),
    )

private fun <Arguments> multiAgentCase(
    name: String,
    serializer: SerializationStrategy<Arguments>,
    arguments: Arguments,
    operation: (Arguments) -> PendingMultiAgentInvocation,
): Pair<ResponseItem.FunctionCall, PendingMultiAgentInvocation> =
    functionCall(name, serializer, arguments) to operation(arguments)
