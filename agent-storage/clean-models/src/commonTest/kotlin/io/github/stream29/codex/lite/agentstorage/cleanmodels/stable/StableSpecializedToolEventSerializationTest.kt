package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ClickOperation
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchQuery
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.SearchResponseLength
import io.github.stream29.codex.lite.tool.imagegeneration.GeneratedImageOutput
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentResult
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsResult
import io.github.stream29.codex.lite.tool.multiagent.ListedAgent
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentStatus
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentResult
import io.github.stream29.codex.lite.tool.multiagent.SpawnForkMode
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentResult
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchExecution
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchResult
import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageDetail
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolOutput
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val specializedToolJson = Json

val stableSpecializedToolEventSerializationTest by testSuite {
    test("round trips tool search event with contract DTOs") {
        assertStableToolEventRoundTrip(
            event = StableToolSearchEvent(
                execution = ToolSearchExecution.Client,
                arguments = SearchToolCallParams("connected drive tools", limit = 4),
                result = ToolSearchResult.Success(
                    tools = listOf(
                        ResponsesApiNamespace(
                            name = "google_drive",
                            description = "Connected Google Drive tools.",
                            tools = listOf(
                                ResponsesApiTool(
                                    name = "search",
                                    description = "Search Drive.",
                                    parameters = ObjectPropertyDefinition(),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            serialName = "tool_search_event",
        )
    }

    test("round trips image events with contract DTOs") {
        assertStableToolEventRoundTrip(
            event = StableImageViewToolEvent(
                arguments = ViewImageToolArguments(
                    path = "/tmp/chart.png",
                    detail = ViewImageDetail.Original,
                ),
                result = StableImageViewResult.Success(
                    ViewImageToolOutput(
                        imageUrl = "data:image/png;base64,aW1hZ2U=",
                        detail = ViewImageDetail.Original,
                    ),
                ),
            ),
            serialName = "image_view_tool_event",
        )
        assertStableToolEventRoundTrip(
            event = StableImageGenerationToolEvent(
                request = StableImageGenerationRequest.Tool(
                    ImageGenToolArguments(
                        prompt = "Draw a compact architecture diagram.",
                        referencedImagePaths = listOf("reference.png"),
                    ),
                ),
                result = StableImageGenerationResult.Success(
                    output = GeneratedImageOutput(
                        result = "Z2VuZXJhdGVk",
                        outputHint = "Saved generated image.",
                    ),
                    savedPath = "generated_images/diagram.png",
                    revisedPrompt = "A compact software architecture diagram.",
                ),
            ),
            serialName = "image_generation_tool_event",
        )
    }

    test("round trips command execution actions with contract DTOs") {
        val events = listOf(
            StableCommandExecutionToolEvent(
                action = StableCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(
                        command = "./gradlew check",
                        workdir = "CodexLite",
                        tty = true,
                        maxOutputTokens = 20_000,
                    ),
                ),
                result = StableCommandExecutionResult.Output(
                    UnifiedExecOutput(
                        chunkId = "chunk-1",
                        wallTimeSeconds = 1.25,
                        exitCode = 0,
                        originalTokenCount = 120,
                        output = "BUILD SUCCESSFUL",
                    ),
                ),
            ),
            StableCommandExecutionToolEvent(
                action = StableCommandExecutionAction.WriteStdin(
                    WriteStdinArguments(sessionId = 42, chars = "\u0003"),
                ),
                result = StableCommandExecutionResult.Failure("Session 42 is not running."),
            ),
            StableCommandExecutionToolEvent(
                action = StableCommandExecutionAction.LocalShell(
                    command = listOf("git", "status", "--short"),
                    workingDirectory = "/workspace",
                    environment = mapOf("TERM" to "dumb"),
                ),
                result = StableCommandExecutionResult.Status(
                    StableCommandExecutionStatus.Completed,
                ),
            ),
        )

        events.forEach { event ->
            assertStableToolEventRoundTrip(event, "command_execution_tool_event")
        }
    }

    test("round trips every multi-agent operation with contract DTOs") {
        val operations = listOf(
            StableMultiAgentOperation.SpawnAgent(
                arguments = SpawnAgentArgs(
                    taskName = "review",
                    message = "Review the clean model.",
                    forkTurns = SpawnForkMode.Recent(3),
                ),
                result = StableSpawnAgentResult.Success(
                    SpawnAgentResult("/root/review", nickname = "reviewer"),
                ),
            ),
            StableMultiAgentOperation.SendMessage(
                arguments = SendMessageArgs("/root/review", "Focus on serialization."),
                result = StableAgentDeliveryResult.Success(""),
            ),
            StableMultiAgentOperation.FollowupTask(
                arguments = FollowupTaskArgs("/root/review", "Inspect field naming."),
                result = StableAgentDeliveryResult.Failure("Agent is unavailable."),
            ),
            StableMultiAgentOperation.WaitAgent(
                arguments = WaitAgentArgs(timeoutMs = 30_000),
                result = StableWaitAgentResult.Success(
                    WaitAgentResult("Wait timed out.", timedOut = true),
                ),
            ),
            StableMultiAgentOperation.InterruptAgent(
                arguments = InterruptAgentArgs("/root/review"),
                result = StableInterruptAgentResult.Success(
                    InterruptAgentResult(MultiAgentStatus.Running),
                ),
            ),
            StableMultiAgentOperation.ListAgents(
                arguments = ListAgentsArgs("/root"),
                result = StableListAgentsResult.Success(
                    ListAgentsResult(
                        listOf(ListedAgent("/root/review", MultiAgentStatus.Idle)),
                    ),
                ),
            ),
        )

        operations.forEach { operation ->
            assertStableToolEventRoundTrip(
                StableMultiAgentToolEvent(operation),
                "multi_agent_tool_event",
            )
        }
    }

    test("round trips request user input event with contract DTOs") {
        val arguments = RequestUserInputArgs(
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
        )
        assertStableToolEventRoundTrip(
            event = StableRequestUserInputToolEvent(
                arguments = arguments,
                result = StableRequestUserInputResult.Answered(
                    RequestUserInputResponse(
                        answers = mapOf(
                            "scope" to RequestUserInputAnswer(listOf("Current module")),
                        ),
                    ),
                ),
            ),
            serialName = "request_user_input_tool_event",
        )
    }

    test("round trips web search event with OpenAI DTOs") {
        val commands = SearchCommands(
            searchQuery = listOf(
                SearchQuery(
                    q = "Kotlin serialization",
                    recency = 30,
                    domains = listOf("kotlinlang.org"),
                ),
            ),
            imageQuery = listOf(SearchQuery("waterfalls")),
            click = listOf(ClickOperation("turn0fetch0", id = 3)),
            responseLength = SearchResponseLength.Long,
        )
        assertStableToolEventRoundTrip(
            event = StableWebSearchToolEvent(
                request = StableWebSearchRequest.WebRun(commands),
                result = StableWebSearchResult.Success(
                    SearchResponse(output = "Search result text."),
                ),
            ),
            serialName = "web_search_tool_event",
        )
    }
}

private fun assertStableToolEventRoundTrip(
    event: StableCleanEvent,
    serialName: String,
) {
    val encoded = specializedToolJson.encodeToString(event)
    val element = specializedToolJson.parseToJsonElement(encoded).jsonObject

    assertEquals(JsonPrimitive(serialName), element["type"])
    assertEquals(event, specializedToolJson.decodeFromString<StableCleanEvent>(encoded))
}
