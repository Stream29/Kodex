package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryContentRenderer
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.imagegeneration.*
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.toolsearch.*
import io.github.stream29.kodex.tool.unifiedexec.*
import io.github.stream29.kodex.tool.viewimage.*
import io.github.stream29.kodex.utils.applypatch.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import kotlin.test.*

private val emptyHistoryArguments = JsonObject(emptyMap())

val offscreenHistoryEventsTest by testSuite {
    val pending = listOf(
        Triple(PendingFunctionToolEvent("function", name = "external", arguments = emptyHistoryArguments),
            "Running external", "Tool: external"),
        Triple(PendingCustomToolEvent("custom", name = "external", input = "raw input"),
            "Running external", "Tool: external"),
        Triple(PendingCommandExecutionToolEvent("exec", action = PendingCommandExecutionAction.ExecCommand(ExecCommandArguments("pwd", yieldTimeMillis = 10_000))),
            "Running: pwd", "Tool: exec_command"),
        Triple(PendingCommandExecutionToolEvent("poll", action = PendingCommandExecutionAction.WriteStdin(WriteStdinArguments(77, yieldTimeMillis = 250))),
            "Waiting for terminal session 77", "Tool: write_stdin"),
        Triple(PendingImageGenerationToolEvent("generate", arguments = ImageGenToolArguments("a prompt")),
            "Generating an image: a prompt", "Tool: image_gen.imagegen"),
        Triple(PendingImageViewToolEvent("view", arguments = ViewImageToolArguments("/image.png")),
            "Viewing image: /image.png", "Tool: view_image"),
        Triple(PendingMcpToolEvent("mcp", name = "inspect", namespace = "mcp", arguments = emptyHistoryArguments),
            "Running mcp.inspect", "Tool: mcp.inspect"),
        Triple(PendingRequestUserInputToolEvent("input", arguments = RequestUserInputArgs(emptyList())),
            "Waiting for user input", "Tool: request_user_input"),
        Triple(PendingSuggestSubagentTaskToolEvent("suggest", arguments = SuggestSubagentTaskArgs(emptyList())),
            "Waiting for approval to create 0 Session(s)", "Tool: suggest_subagent_task"),
        Triple(PendingToolSearchEvent("search", arguments = SearchToolCallParams("lookup")),
            "Searching available tools: lookup", "Tool: tool_search"),
        Triple(PendingWebSearchToolEvent("web", commands = SearchCommands(searchQuery = listOf(SearchQuery("Kotlin")))),
            "Searching the web: Kotlin", "Tool: web.run"),
        Triple(PendingInvalidToolCall("invalid", invocation = PendingInvalidToolInvocation.Function(
            name = "exec_command", arguments = "{BROKEN"), message = "Invalid"),
            "Model emitted an invalid tool call", "error: Invalid"),
        Triple(PendingServerToolSearch(ResponseItem.ServerToolSearchCall(arguments = emptyHistoryArguments)),
            "Searching cloud tools", "Tool: server_tool_search"),
    )
    for ((index, case) in pending.withIndex()) {
        test("shared pending dispatch uses real graphical disclosure branch=$index") { offscreenTest {
            val scene = OffscreenScene.create { HistoryEventContent(case.first, rememberComposeHistoryContentRenderer()) }
            try {
                scene.settle { scene.hasDescription("History tool: ${case.second}") }
                assertFalse(scene.hasText(case.third))
                scene.clickDescription("History tool: ${case.second}")
                scene.settle { scene.hasText(case.third) }
                if (index == 3) assertFalse(scene.hasText("> Process"))
                scene.frame()
            } finally { scene.close() }
        } }
    }

    test("JSON fallback keeps lazy sections and suppresses inline media before rendering") { offscreenTest {
        val event = StableJsonToolEvent("json", name = "external", arguments = emptyHistoryArguments,
            result = Json.parseToJsonElement("""{"nested":{"image_url":"data:image/png;base64,SECRET"}}"""))
        val scene = OffscreenScene.create { HistoryEventContent(event, rememberComposeHistoryContentRenderer()) }
        try {
            scene.settle { scene.hasDescription("History tool: external") }
            scene.clickDescription("History tool: external")
            scene.settle { scene.hasText("> Result") }
            assertFalse(scene.hasText("Result: [structured data with inline media]"))
            scene.click("> Result")
            scene.settle { scene.hasText("Result: [structured data with inline media]") }
            assertFalse(scene.containsHistoryText("SECRET"))
            scene.frame()
        } finally { scene.close() }
    } }

    test("MCP image output is a safe reference and definitions remain lazy") { offscreenTest {
        val event = StableMcpToolEvent("mcp", name = "inspect", namespace = "mcp", arguments = emptyHistoryArguments,
            result = CallToolResult(content = listOf(Json.parseToJsonElement("""{"type":"image","data":"BASE64SECRET"}"""))))
        val scene = OffscreenScene.create { HistoryEventContent(event, rememberComposeHistoryContentRenderer()) }
        try {
            scene.settle { scene.hasDescription("History tool: mcp.inspect") }
            scene.clickDescription("History tool: mcp.inspect")
            scene.settle { scene.hasText("> Result") }
            scene.click("> Result")
            scene.settle { scene.hasText("Image: [inline image, 12 characters]") }
            assertFalse(scene.containsHistoryText("BASE64SECRET"))
            scene.frame()
        } finally { scene.close() }
    } }

    val recorded = listOf(
        Triple<StableCleanEvent, String, String>(
            StableImageGenerationToolEvent("image", arguments = ImageGenToolArguments("full\nprompt"),
                result = StableImageGenerationResult.Success(GeneratedImageOutput("BASE64SECRET", "saved"), "/out/final.png")),
            "Generate final.png", "saved_path: /out/final.png",
        ),
        Triple(
            StableWebSearchCall(ResponseItem.WebSearchCall(action = WebSearchAction.Search("first", listOf("second", "third")))),
            "Search first", "hosted_web_search",
        ),
        Triple(
            StableToolSearchEvent("search", arguments = SearchToolCallParams("lookup"),
                result = ToolSearchResult.InvalidArguments("Invalid query")),
            "Failed to search available tools", "error: Invalid query",
        ),
        Triple(
            StableTextToolEvent("utility", name = "get_context_remaining", arguments = emptyHistoryArguments,
                result = "Original returned sentence.", success = true),
            "Check remaining context", "Original returned sentence.",
        ),
        Triple(
            StableImageViewToolEvent("view", arguments = ViewImageToolArguments("/image.png", ViewImageDetail.Original),
                result = StableImageViewResult.Success(ViewImageToolOutput("data:image/png;base64,SECRET", ViewImageDetail.High))),
            "View image: /image.png", "output_detail: high",
        ),
    )
    for ((index, case) in recorded.withIndex()) {
        test("shared recorded built-in content keeps its specific fields branch=$index") { offscreenTest {
            val scene = OffscreenScene.create { HistoryEventContent(case.first, rememberComposeHistoryContentRenderer()) }
            try {
                scene.settle { scene.hasDescription("History tool: ${case.second}") }
                scene.clickDescription("History tool: ${case.second}")
                scene.settle { scene.hasText(case.third) }
                assertFalse(scene.containsHistoryText("BASE64SECRET"))
                assertFalse(scene.containsHistoryText("data:image/png;base64,SECRET"))
                assertFalse(scene.hasText("> Arguments"))
                if (index == 1) {
                    assertTrue(scene.hasText("v Search first\nSearch second\nSearch third"))
                    assertFalse(scene.hasText("output"))
                }
                scene.frame()
            } finally { scene.close() }
        } }
    }

    test("reasoning exposes only provider summary and keeps full reasoning private") { offscreenTest {
        val event = StableReasoning(ResponseItem.Reasoning(
            summary = listOf(ReasoningItemReasoningSummary.SummaryText("short summary")),
            content = listOf(ReasoningItemContent.ReasoningText("private full reasoning")),
        ))
        val scene = OffscreenScene.create { HistoryEventContent(event, rememberComposeHistoryContentRenderer()) }
        try {
            scene.settle { scene.hasDescription("History disclosure: Think") }
            assertFalse(scene.hasText("short summary"))
            scene.clickDescription("History disclosure: Think")
            scene.settle { scene.hasText("short summary") }
            assertFalse(scene.containsHistoryText("private full reasoning"))
            scene.frame()
        } finally { scene.close() }
    } }

    test("patch keeps a bounded first page and explicit show-more retains original line order") { offscreenTest {
        val diff = Patch(patch = "", hunks = listOf(AddFileHunk("new.txt", (0..399).joinToString("\n") { "line $it" })))
        val event = PendingPatchToolEvent("patch", diff = diff)
        val scene = OffscreenScene.create { HistoryEventContent(event, rememberComposeHistoryContentRenderer()) }
        try {
            scene.settle { scene.hasDescription("History tool: Editing new.txt") }
            scene.clickDescription("History tool: Editing new.txt")
            scene.settle { scene.hasText("+ line 198") }
            assertFalse(scene.hasText("+ line 199"))
            val showMore = scene.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                    ?.contains("Show more patch lines") == true }
            assertTrue(assertNotNull(showMore.config[SemanticsActions.OnClick].action).invoke())
            scene.settle { scene.hasText("+ line 398") }
            assertFalse(scene.hasText("+ line 399"))
            scene.frame()
        } finally { scene.close() }
    } }

    test("streaming tool replay merges exact input deltas behind a lazy section") { offscreenTest {
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 16)
        events.tryEmit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.FunctionCall(
            id = ResponseItemId("tool"), name = "run", namespace = "shell", arguments = "{", callId = "call",
        )))
        events.tryEmit(ResponsesStreamEvent.ToolCallInputDelta(itemId = "tool", callId = "call", delta = "\"command\":\"pwd\""))
        events.tryEmit(ResponsesStreamEvent.ToolCallInputDelta(callId = "call", delta = ",\"tty\":true}"))
        val scene = OffscreenScene.create {
            HistoryStreamingContent(HistoryStreamingItem.Output(HistoryStreamingKind.ToolCall, events), rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasDescription("History tool: Running a command") }
            scene.clickDescription("History tool: Running a command")
            scene.settle { scene.hasText("> Input") }
            assertFalse(scene.hasText("Input: {\"command\":\"pwd\",\"tty\":true}"))
            scene.click("> Input")
            scene.settle { scene.hasText("Input: {\"command\":\"pwd\",\"tty\":true}") }
            scene.frame()
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.containsHistoryText(fragment: String): Boolean =
    nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { fragment in it.text } }
