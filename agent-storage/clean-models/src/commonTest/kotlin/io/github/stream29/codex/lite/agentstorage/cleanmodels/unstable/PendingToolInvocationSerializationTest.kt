package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import de.infix.testBalloon.framework.core.testSuite
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
                execution = PendingToolSearchExecution.Client,
                query = "connected drive tools",
                limit = 4,
            ) to "tool_search",
            PendingToolInvocation.ImageView(
                path = "/tmp/chart.png",
                detail = PendingImageDetail.Original,
                environmentId = "local",
            ) to "image_view",
            PendingToolInvocation.ImageGeneration(
                request = PendingImageGenerationRequest.Tool(
                    prompt = "Draw an architecture diagram.",
                    referencedImagePaths = listOf("reference.png"),
                    numLastImagesToInclude = 1,
                ),
            ) to "image_generation",
            PendingToolInvocation.CommandExecution(
                action = PendingCommandExecutionAction.ExecCommand(
                    command = "./gradlew check",
                    workdir = "CodexLite",
                    shell = "/bin/bash",
                    tty = true,
                    yieldTimeMillis = 10_000,
                    maxOutputTokens = 20_000,
                ),
            ) to "command_execution",
            PendingToolInvocation.MultiAgent(
                operation = PendingMultiAgentInvocation.SpawnAgent(
                    taskName = "review",
                    message = "Review the clean model.",
                    forkTurns = PendingAgentForkMode.Recent(3),
                    model = "gpt-5.6",
                    reasoningEffort = "high",
                    serviceTier = "priority",
                ),
            ) to "multi_agent",
            PendingToolInvocation.RequestUserInput(
                questions = listOf(
                    PendingRequestUserInputQuestion(
                        id = "scope",
                        header = "Scope",
                        question = "Which scope should be used?",
                        allowsOther = true,
                        options = listOf(
                            PendingRequestUserInputOption(
                                label = "Current module",
                                description = "Only update clean-models.",
                            ),
                        ),
                    ),
                ),
                autoResolutionMillis = 60_000,
            ) to "request_user_input",
            PendingToolInvocation.WebSearch(
                source = PendingWebSearchSource.WebRun,
                operations = listOf(
                    PendingWebSearchOperation.SearchQuery(
                        query = "Kotlin serialization",
                        domains = listOf("kotlinlang.org"),
                    ),
                ),
                responseLength = PendingWebSearchResponseLength.Short,
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

    test("round trips every Multi-agent pending operation") {
        val operations = listOf(
            PendingMultiAgentInvocation.SpawnAgent(
                taskName = "review",
                message = "Review the clean model.",
                forkTurns = PendingAgentForkMode.All,
            ),
            PendingMultiAgentInvocation.SendMessage(
                target = "/root/review",
                message = "Focus on serialization.",
            ),
            PendingMultiAgentInvocation.FollowupTask(
                target = "/root/review",
                message = "Also inspect field naming.",
            ),
            PendingMultiAgentInvocation.WaitAgent(timeoutMillis = 30_000),
            PendingMultiAgentInvocation.InterruptAgent(target = "/root/review"),
            PendingMultiAgentInvocation.ListAgents(pathPrefix = "/root"),
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
