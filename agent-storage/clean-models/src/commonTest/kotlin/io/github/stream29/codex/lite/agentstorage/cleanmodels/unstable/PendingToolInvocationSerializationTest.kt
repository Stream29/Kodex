package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchQuery
import io.github.stream29.codex.lite.openai.SearchResponseLength
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnForkMode
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchExecution
import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageDetail
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

private val invocationJson = Json

val pendingToolInvocationSerializationTest by testSuite {
    test("round trips every pending tool invocation kind") {
        val invocations = listOf(
            PendingToolInvocation.Function(
                name = "search",
                namespace = "google_drive",
                arguments = PendingFunctionArguments.Json(
                    buildJsonObject {
                        put("query", "design context")
                    },
                ),
            ) to "function",
            PendingToolInvocation.Custom(
                name = "custom_tool",
                input = "freeform input",
            ) to "custom",
            PendingToolInvocation.ApplyPatch(
                diff = """
                    *** Begin Patch
                    *** Delete File: obsolete.txt
                    *** End Patch
                    """.trimIndent().parsePatch(),
            ) to "apply_patch",
            PendingToolInvocation.ToolSearch(
                execution = ToolSearchExecution.Client,
                arguments = SearchToolCallParams("connected drive tools", limit = 4),
            ) to "tool_search",
            PendingToolInvocation.ImageView(
                ViewImageToolArguments(
                    path = "/tmp/chart.png",
                    detail = ViewImageDetail.Original,
                    environmentId = "local",
                ),
            ) to "image_view",
            PendingToolInvocation.ImageGeneration(
                request = PendingImageGenerationRequest.Tool(
                    ImageGenToolArguments(
                        prompt = "Draw an architecture diagram.",
                        referencedImagePaths = listOf("reference.png"),
                    ),
                ),
            ) to "image_generation",
            PendingToolInvocation.CommandExecution(
                action = PendingCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(
                        command = "./gradlew check",
                        workdir = "CodexLite",
                        tty = true,
                        maxOutputTokens = 20_000,
                    ),
                ),
            ) to "command_execution",
            PendingToolInvocation.MultiAgent(
                operation = PendingMultiAgentInvocation.SpawnAgent(
                    SpawnAgentArgs(
                        taskName = "review",
                        message = "Review the clean model.",
                        forkTurns = SpawnForkMode.Recent(3),
                    ),
                ),
            ) to "multi_agent",
            PendingToolInvocation.RequestUserInput(
                RequestUserInputArgs(
                    questions = listOf(
                        RequestUserInputQuestion(
                            id = "scope",
                            header = "Scope",
                            question = "Which scope should be used?",
                            isOther = true,
                            options = listOf(
                                RequestUserInputQuestionOption(
                                    label = "Current module",
                                    description = "Only update clean-models.",
                                ),
                            ),
                        ),
                    ),
                    autoResolutionMs = 60_000,
                ),
            ) to "request_user_input",
            PendingToolInvocation.WebSearch(
                PendingWebSearchRequest.WebRun(
                    SearchCommands(
                        searchQuery = listOf(
                            SearchQuery(
                                q = "Kotlin serialization",
                                domains = listOf("kotlinlang.org"),
                            ),
                        ),
                        responseLength = SearchResponseLength.Short,
                    ),
                ),
            ) to "web_search",
        )

        invocations.forEach { (invocation, serialName) ->
            val encoded = invocationJson.encodeToString<PendingToolInvocation>(invocation)
            val element = invocationJson.parseToJsonElement(encoded).jsonObject

            assertEquals(JsonPrimitive(serialName), element["type"])
            assertEquals(
                invocation,
                invocationJson.decodeFromString<PendingToolInvocation>(encoded),
            )
        }
    }

    test("round trips every multi-agent pending operation") {
        val operations = listOf(
            PendingMultiAgentInvocation.SpawnAgent(
                SpawnAgentArgs(
                    taskName = "review",
                    message = "Review the clean model.",
                    forkTurns = SpawnForkMode.All,
                ),
            ),
            PendingMultiAgentInvocation.SendMessage(
                SendMessageArgs("/root/review", "Focus on serialization."),
            ),
            PendingMultiAgentInvocation.FollowupTask(
                FollowupTaskArgs("/root/review", "Also inspect field naming."),
            ),
            PendingMultiAgentInvocation.WaitAgent(WaitAgentArgs(timeoutMs = 30_000)),
            PendingMultiAgentInvocation.InterruptAgent(InterruptAgentArgs("/root/review")),
            PendingMultiAgentInvocation.ListAgents(ListAgentsArgs("/root")),
        )

        operations.forEach { operation ->
            val encoded = invocationJson.encodeToString<PendingMultiAgentInvocation>(operation)

            assertEquals(
                operation,
                invocationJson.decodeFromString<PendingMultiAgentInvocation>(encoded),
            )
        }
    }

    test("retains invalid function argument text") {
        val invocation: PendingToolInvocation = PendingToolInvocation.Function(
            name = "broken_tool",
            arguments = PendingFunctionArguments.InvalidJson("""{"missing":"""),
        )

        val encoded = invocationJson.encodeToString(invocation)
        val arguments = invocationJson.parseToJsonElement(encoded)
            .jsonObject
            .getValue("arguments")
            .jsonObject

        assertEquals(JsonPrimitive("invalid_json"), arguments["type"])
        assertEquals(JsonPrimitive("""{"missing":"""), arguments["raw"])
        assertEquals(
            invocation,
            invocationJson.decodeFromString<PendingToolInvocation>(encoded),
        )
    }
}
