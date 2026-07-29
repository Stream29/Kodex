package io.github.stream29.codex.lite.agentstorage.cleanmodels

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val specializedToolJson = Json

val stableSpecializedToolEventSerializationTest by testSuite {
    test("round trips tool search event") {
        assertStableToolEventRoundTrip(
            event = StableToolSearchEvent(
                execution = StableToolSearchExecution.Client,
                query = "connected drive tools",
                limit = 4,
                result = StableToolSearchResult.Success(
                    tools = listOf(
                        StableToolSearchTool.Namespace(
                            name = "google_drive",
                            description = "Connected Google Drive tools.",
                            tools = listOf(
                                StableToolSearchTool.Function(
                                    name = "search",
                                    description = "Search Drive.",
                                    deferLoading = true,
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

    test("round trips image view event") {
        assertStableToolEventRoundTrip(
            event = StableImageViewToolEvent(
                path = "/tmp/chart.png",
                detail = StableImageDetail.Original,
                result = StableImageViewResult.Success(
                    imageUrl = "data:image/png;base64,aW1hZ2U=",
                    detail = StableImageDetail.Original,
                ),
            ),
            serialName = "image_view_tool_event",
        )
    }

    test("round trips image generation event") {
        assertStableToolEventRoundTrip(
            event = StableImageGenerationToolEvent(
                request = StableImageGenerationRequest.Tool(
                    prompt = "Draw a compact architecture diagram.",
                    referencedImagePaths = listOf("reference.png"),
                ),
                result = StableImageGenerationResult.Success(
                    imageUrl = "data:image/png;base64,Z2VuZXJhdGVk",
                    outputHint = "Saved generated image.",
                    savedPath = "generated_images/diagram.png",
                    revisedPrompt = "A compact software architecture diagram.",
                ),
            ),
            serialName = "image_generation_tool_event",
        )
    }

    test("round trips command execution actions") {
        val events = listOf(
            StableCommandExecutionToolEvent(
                action = StableCommandExecutionAction.ExecCommand(
                    command = "./gradlew check",
                    workdir = "CodexLite",
                    shell = "/bin/bash",
                    tty = true,
                    yieldTimeMillis = 10_000,
                    maxOutputTokens = 20_000,
                ),
                result = StableCommandExecutionResult.Output(
                    chunkId = "chunk-1",
                    wallTimeSeconds = 1.25,
                    exitCode = 0,
                    originalTokenCount = 120,
                    output = "BUILD SUCCESSFUL",
                ),
            ),
            StableCommandExecutionToolEvent(
                action = StableCommandExecutionAction.WriteStdin(
                    sessionId = 42,
                    chars = "\u0003",
                    yieldTimeMillis = 250,
                    maxOutputTokens = 10_000,
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

    test("round trips every multi-agent operation") {
        val operations = listOf(
            StableMultiAgentOperation.SpawnAgent(
                taskName = "review",
                message = "Review the clean model.",
                forkTurns = StableAgentForkMode.Recent(3),
                model = "gpt-5.6",
                reasoningEffort = "high",
                serviceTier = "priority",
                result = StableSpawnAgentResult.Success(
                    agentPath = "/root/review",
                    nickname = "reviewer",
                ),
            ),
            StableMultiAgentOperation.SendMessage(
                target = "/root/review",
                message = "Please focus on serialization.",
                result = StableAgentDeliveryResult.Success,
            ),
            StableMultiAgentOperation.FollowupTask(
                target = "/root/review",
                message = "Also inspect field naming.",
                result = StableAgentDeliveryResult.Failure("Agent is unavailable."),
            ),
            StableMultiAgentOperation.WaitAgent(
                timeoutMillis = 30_000,
                result = StableWaitAgentResult.Success(
                    message = "Wait timed out.",
                    timedOut = true,
                ),
            ),
            StableMultiAgentOperation.InterruptAgent(
                target = "/root/review",
                result = StableInterruptAgentResult.Success(
                    previousStatus = StableAgentStatus.Running,
                ),
            ),
            StableMultiAgentOperation.ListAgents(
                pathPrefix = "/root",
                result = StableListAgentsResult.Success(
                    agents = listOf(
                        StableListedAgent(
                            agentPath = "/root/review",
                            status = StableAgentStatus.Idle,
                        ),
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

    test("round trips request user input event") {
        assertStableToolEventRoundTrip(
            event = StableRequestUserInputToolEvent(
                questions = listOf(
                    StableRequestUserInputQuestion(
                        id = "scope",
                        header = "Scope",
                        question = "Which scope should be used?",
                        allowsOther = true,
                        options = listOf(
                            StableRequestUserInputOption(
                                label = "Current module",
                                description = "Only update clean-models.",
                            ),
                        ),
                    ),
                ),
                autoResolutionMillis = 60_000,
                result = StableRequestUserInputResult.Answered(
                    answers = mapOf(
                        "scope" to StableRequestUserInputAnswer(
                            values = listOf("Current module"),
                        ),
                    ),
                ),
            ),
            serialName = "request_user_input_tool_event",
        )
    }

    test("round trips all web search operations") {
        assertStableToolEventRoundTrip(
            event = StableWebSearchToolEvent(
                source = StableWebSearchSource.WebRun,
                operations = listOf(
                    StableWebSearchOperation.SearchQuery(
                        query = "Kotlin serialization",
                        recencyDays = 30,
                        domains = listOf("kotlinlang.org"),
                    ),
                    StableWebSearchOperation.ImageQuery("waterfalls"),
                    StableWebSearchOperation.Open("turn0search0", line = 120),
                    StableWebSearchOperation.Click("turn0fetch0", linkId = 3),
                    StableWebSearchOperation.Find("turn0fetch0", pattern = "Serializable"),
                    StableWebSearchOperation.Screenshot("turn0view0", pageNumber = 0),
                    StableWebSearchOperation.Finance(
                        ticker = "AMD",
                        assetType = StableFinanceAssetType.Equity,
                        market = "USA",
                    ),
                    StableWebSearchOperation.Weather(
                        location = "Singapore",
                        durationDays = 7,
                    ),
                    StableWebSearchOperation.Sports(
                        function = StableSportsFunction.Schedule,
                        league = StableSportsLeague.Nba,
                        team = "GSW",
                    ),
                    StableWebSearchOperation.Time("+08:00"),
                    StableWebSearchOperation.Other,
                ),
                responseLength = StableWebSearchResponseLength.Long,
                result = StableWebSearchResult.Success("Search result text."),
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
