package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchQuery
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.SearchResponseLength
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
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
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val specializedToolJson = Json

val stableSpecializedToolEventSerializationTest by testSuite {
    test("round trips tool-search and image events") {
        val events: List<StableCleanEvent.CompletedTool> = listOf(
            StableToolSearchEvent(
                callId = "call_tool_search",
                itemId = ResponseItemId("item_tool_search"),
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
            StableImageViewToolEvent(
                callId = "call_view_image",
                itemId = ResponseItemId("item_view_image"),
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
            StableImageGenerationToolEvent(
                callId = "call_imagegen",
                itemId = ResponseItemId("item_imagegen"),
                arguments = ImageGenToolArguments(
                    prompt = "Draw a compact architecture diagram.",
                    referencedImagePaths = listOf("reference.png"),
                ),
                result = StableImageGenerationResult.Success(
                    output = GeneratedImageOutput(
                        result = "Z2VuZXJhdGVk",
                        outputHint = "Saved generated image.",
                    ),
                    savedPath = "generated_images/diagram.png",
                ),
            ),
        )

        events.forEach(::assertStableToolEventRoundTrip)
        assertIs<ResponseItem.ClientToolSearchCall>(
            events[0].toResponseHistoryItems().first(),
        )
        assertEquals(
            listOf("view_image", "imagegen"),
            events.drop(1).map { event -> event.projectedFunctionName() },
        )
    }

    test("round trips command execution actions") {
        val events = listOf(
            StableCommandExecutionToolEvent(
                callId = "call_exec_command",
                itemId = ResponseItemId("item_exec_command"),
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
                callId = "call_write_stdin",
                itemId = ResponseItemId("item_write_stdin"),
                action = StableCommandExecutionAction.WriteStdin(
                    WriteStdinArguments(sessionId = 42, chars = "\u0003"),
                ),
                result = StableCommandExecutionResult.Failure("Session 42 is not running."),
            ),
        )

        events.forEach(::assertStableToolEventRoundTrip)
        assertEquals(
            listOf("exec_command", "write_stdin"),
            events.map { event -> event.projectedFunctionName() },
        )
    }

    test("round trips every multi-agent operation") {
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

        val events = operations.mapIndexed { index, operation ->
            val callId = "call_multi_agent_$index"
            StableMultiAgentToolEvent(
                callId = callId,
                itemId = ResponseItemId("item_multi_agent_$index"),
                operation = operation,
            )
        }
        events.forEach(::assertStableToolEventRoundTrip)
        assertEquals(
            listOf(
                "spawn_agent",
                "send_message",
                "followup_task",
                "wait_agent",
                "interrupt_agent",
                "list_agents",
            ),
            events.map { event -> event.projectedFunctionName() },
        )
    }

    test("round trips request-user-input, plan, and web-search events") {
        val requestArguments = RequestUserInputArgs(
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
        val commands = SearchCommands(
            searchQuery = listOf(
                SearchQuery(
                    q = "Kotlin serialization",
                    domains = listOf("kotlinlang.org"),
                ),
            ),
            responseLength = SearchResponseLength.Short,
        )
        val events = listOf(
            StableRequestUserInputToolEvent(
                callId = "call_request_user_input",
                itemId = ResponseItemId("item_request_user_input"),
                arguments = requestArguments,
                result = StableRequestUserInputResult.Answered(
                    RequestUserInputResponse(
                        answers = mapOf(
                            "scope" to RequestUserInputAnswer(listOf("Current module")),
                        ),
                    ),
                ),
            ),
            StablePlanUpdate(
                callId = "call_update_plan",
                itemId = ResponseItemId("item_update_plan"),
                arguments = UpdatePlanArgs(
                    explanation = "Current plan",
                    plan = listOf(
                        PlanItemArg("Migrate clean tools", StepStatus.InProgress),
                    ),
                ),
            ),
            StableWebSearchToolEvent(
                callId = "call_run",
                itemId = ResponseItemId("item_run"),
                commands = commands,
                result = StableWebSearchResult.Success(
                    SearchResponse(output = "Search result text."),
                ),
            ),
        )

        events.forEach(::assertStableToolEventRoundTrip)
        assertEquals(
            listOf("request_user_input", "update_plan", "run"),
            events.map { event -> event.projectedFunctionName() },
        )
    }
}

private fun assertStableToolEventRoundTrip(event: StableCleanEvent.CompletedTool) {
    val encoded = specializedToolJson.encodeToString<StableCleanEvent>(event)
    val element = specializedToolJson.parseToJsonElement(encoded).jsonObject
    val items = event.toResponseHistoryItems()
    val call = assertIs<ResponseItem.ToolCall>(items.first())
    val output = assertIs<ResponseItem.ToolCallOutput>(items.last())

    assertEquals(event, specializedToolJson.decodeFromString<StableCleanEvent>(encoded))
    assertEquals(2, items.size)
    assertEquals(call.callId, output.callId)
    assertTrue("type" in element)
    assertTrue("call" !in element)
    assertTrue("output" !in element)
}

private fun StableCleanEvent.CompletedTool.projectedFunctionName(): String =
    assertIs<ResponseItem.FunctionCall>(toResponseHistoryItems().first()).name
