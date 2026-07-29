package io.github.stream29.codex.lite.agentstate.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionArguments
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingImageGenerationRequest
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolInvocation
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingWebSearchRequest
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchQuery
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
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestion
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
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

val pendingToolEventProjectionTest by testSuite {
    test("projects specialized function calls with their contract arguments") {
        val exec = ExecCommandArguments(command = "./gradlew check", workdir = "CodexLite")
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
        val cases = listOf(
            functionCall(
                UnifiedExecTools.ExecCommandName,
                ExecCommandArguments.serializer(),
                exec,
            ) to PendingToolInvocation.CommandExecution(
                PendingCommandExecutionAction.ExecCommand(exec),
            ),
            functionCall(
                UnifiedExecTools.WriteStdinName,
                WriteStdinArguments.serializer(),
                write,
            ) to PendingToolInvocation.CommandExecution(
                PendingCommandExecutionAction.WriteStdin(write),
            ),
            functionCall(
                WebRunToolName,
                SearchCommands.serializer(),
                web,
                namespace = WebRunNamespace,
            ) to PendingToolInvocation.WebSearch(PendingWebSearchRequest.WebRun(web)),
            functionCall(
                ImageGenToolName,
                ImageGenToolArguments.serializer(),
                imageGeneration,
                namespace = ImageGenNamespace,
            ) to PendingToolInvocation.ImageGeneration(
                PendingImageGenerationRequest.Tool(imageGeneration),
            ),
            functionCall(
                ViewImageTools.Name,
                ViewImageToolArguments.serializer(),
                imageView,
            ) to PendingToolInvocation.ImageView(imageView),
            functionCall(
                RequestUserInputTools.Name,
                RequestUserInputArgs.serializer(),
                requestUserInput,
            ) to PendingToolInvocation.RequestUserInput(requestUserInput),
        )

        cases.forEach { (call, invocation) ->
            val event = call.toPendingToolEvent()

            assertEquals(call.callId, event.callId)
            assertEquals(invocation, event.invocation)
        }
    }

    test("projects every multi-agent call with its contract arguments") {
        val spawn = SpawnAgentArgs("review", "Review this change.")
        val send = SendMessageArgs("/root/review", "Focus on the model.")
        val followup = FollowupTaskArgs("/root/review", "Continue.")
        val wait = WaitAgentArgs(timeoutMs = 30_000)
        val interrupt = InterruptAgentArgs("/root/review")
        val list = ListAgentsArgs("/root")
        val cases = listOf(
            functionCall(
                MultiAgentTools.SpawnAgentName,
                SpawnAgentArgs.serializer(),
                spawn,
            ) to PendingMultiAgentInvocation.SpawnAgent(spawn),
            functionCall(
                MultiAgentTools.SendMessageName,
                SendMessageArgs.serializer(),
                send,
            ) to PendingMultiAgentInvocation.SendMessage(send),
            functionCall(
                MultiAgentTools.FollowupTaskName,
                FollowupTaskArgs.serializer(),
                followup,
            ) to PendingMultiAgentInvocation.FollowupTask(followup),
            functionCall(
                MultiAgentTools.WaitAgentName,
                WaitAgentArgs.serializer(),
                wait,
            ) to PendingMultiAgentInvocation.WaitAgent(wait),
            functionCall(
                MultiAgentTools.InterruptAgentName,
                InterruptAgentArgs.serializer(),
                interrupt,
            ) to PendingMultiAgentInvocation.InterruptAgent(interrupt),
            functionCall(
                MultiAgentTools.ListAgentsName,
                ListAgentsArgs.serializer(),
                list,
            ) to PendingMultiAgentInvocation.ListAgents(list),
        )

        cases.forEach { (call, operation) ->
            assertEquals(
                PendingToolInvocation.MultiAgent(operation),
                call.toPendingToolEvent().invocation,
            )
        }
    }

    test("projects client tool search and apply patch without field mapping") {
        val searchArguments = SearchToolCallParams("connected drive tools", limit = 3)
        val searchCall = ResponseItem.ClientToolSearchCall(
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
            callId = "call_patch",
            name = ApplyPatchTools.Name,
            input = patchText,
        )

        assertEquals(
            PendingToolInvocation.ToolSearch(
                execution = ToolSearchExecution.Client,
                arguments = searchArguments,
            ),
            searchCall.toPendingToolEvent().invocation,
        )
        assertEquals(
            PendingToolInvocation.ApplyPatch(patchText.parsePatch()),
            patchCall.toPendingToolEvent().invocation,
        )
    }

    test("falls back losslessly when a specialized call cannot be decoded") {
        val call = ResponseItem.FunctionCall(
            callId = "call_invalid",
            name = UnifiedExecTools.ExecCommandName,
            arguments = """{"cmd":""",
        )

        assertEquals(
            PendingToolInvocation.Function(
                name = UnifiedExecTools.ExecCommandName,
                arguments = PendingFunctionArguments.InvalidJson(call.arguments),
            ),
            call.toPendingToolEvent().invocation,
        )
    }

    test("retains dynamic function namespace and JSON arguments") {
        val arguments = buildJsonObject {
            put("query", "design context")
        }
        val call = ResponseItem.FunctionCall(
            callId = "call_dynamic",
            namespace = "google_drive",
            name = "search",
            arguments = arguments.toString(),
        )

        assertEquals(
            PendingToolInvocation.Function(
                name = call.name,
                namespace = call.namespace,
                arguments = PendingFunctionArguments.Json(arguments),
            ),
            call.toPendingToolEvent().invocation,
        )
    }
}

private fun <Arguments> functionCall(
    name: String,
    serializer: SerializationStrategy<Arguments>,
    arguments: Arguments,
    namespace: String? = null,
): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(
        callId = "call_$name",
        namespace = namespace,
        name = name,
        arguments = OpenAiJsonCodec.encodeToString(serializer, arguments),
    )
