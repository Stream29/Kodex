package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.stream29.kodex.app.ui.UiTheme
import io.github.stream29.kodex.cli.patch.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryItemRenderer
import kotlin.time.Duration.Companion.seconds
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
import kotlin.math.abs

private val emptyHistoryArguments = JsonObject(emptyMap())

val offscreenHistoryEventsTest by testSuite {
    for (dark in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
        test("history headings escape button labels dark=$dark scale=$scale") { offscreenTest {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = 360, height = 1600) {
                UiTheme {
                    MaterialTheme(colorScheme = scheme) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            Column {
                                val renderer = rememberComposeHistoryContentRenderer()
                                renderer.Heading("Outside heading", HistoryTextStyle.Bold)
                                renderer.Expandable("Inside heading", "typography", HistoryTextStyle.Bold) {
                                    renderer.Text("Expanded body", HistoryTextStyle.Normal)
                                }
                                renderer.Tool("Tool heading", "inspect", "succeeded", "tool", HistoryTextStyle.Normal, null) {
                                    it.Section("Section heading") { it.Text("Section body", HistoryTextStyle.Normal) }
                                }
                                TextButton(onClick = {}) {
                                    rememberComposeHistoryItemRenderer().TurnMarker(2.seconds)
                                }
                            }
                        }
                    }
                }
            }
            try {
                fun check(value: String, weight: FontWeight) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    val actual = layouts.single().layoutInput.style
                    assertEquals(16.sp, actual.fontSize, value)
                    assertEquals(24.sp, actual.lineHeight, value)
                    assertEquals(weight, actual.fontWeight, value)
                }
                scene.settle { scene.hasText("Inside heading") }
                check("Outside heading", FontWeight.Bold)
                check("Inside heading", FontWeight.Bold)
                check("Worked for 2s", FontWeight.Normal)
                scene.clickDescription("History disclosure: Inside heading")
                scene.settle { scene.hasText("Expanded body") }
                check("Inside heading", FontWeight.Bold)
                check("Expanded body", FontWeight.Normal)
                scene.clickDescription("History tool: Tool heading")
                scene.settle { scene.hasText("Section heading") }
                check("Section heading", FontWeight.Normal)
                scene.click("Section heading")
                scene.settle { scene.hasText("Section body") }
                check("Section body", FontWeight.Normal)
            } finally { scene.close() }
        } }
    }

    for (width in listOf(180, 360)) for (scale in listOf(1f, 1.5f)) for (pending in listOf(false, true)) {
        test("MCP typed JSON preserves fields and wraps width=$width scale=$scale pending=$pending") { offscreenTest {
            val payload = "漢字 " + "preserve ".repeat(23)
            val arguments = Json.parseToJsonElement("""{"query":"$payload","values":[1,true]}""")
            val structured = Json.parseToJsonElement("""[{"value":"$payload"},2,false]""")
            val argsText = "Arguments: $arguments"
            val resultText = "Structured result: $structured"
            val plain = "Content: ordinary text [unchanged]"
            val summary = if (pending) "Running mcp.inspect" else "mcp.inspect"
            val scene = OffscreenScene.create(width = width, height = 2500) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    val renderer = rememberComposeHistoryContentRenderer()
                    if (pending) HistoryEventContent(
                        PendingMcpToolEvent("mcp-pending", name = "inspect", namespace = "mcp", arguments = arguments),
                        renderer,
                    ) else HistoryEventContent(
                        StableMcpToolEvent("mcp-stable", name = "inspect", namespace = "mcp", arguments = arguments,
                            result = CallToolResult(content = listOf(
                                Json.parseToJsonElement("""{"type":"text","text":"ordinary text [unchanged]"}"""),
                                Json.parseToJsonElement("""{"type":"image","data":"BASE64SECRET"}"""),
                            ), structuredContent = structured, meta = JsonPrimitive("META-SECRET"))),
                        renderer,
                    )
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: $summary") }
                scene.clickDescription("History tool: $summary")
                scene.settle { scene.hasText("Arguments") }
                assertFalse(scene.hasText(argsText))
                scene.click("Arguments")
                scene.settle { scene.hasText(argsText) }
                fun layout(value: String): TextLayoutResult {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    return layouts.single()
                }
                assertEquals(argsText, layout(argsText).layoutInput.text.text)
                assertEquals(FontFamily.Monospace, layout(argsText).layoutInput.style.fontFamily)
                assertTrue(layout(argsText).layoutInput.softWrap && layout(argsText).lineCount > 1)
                if (!pending) {
                    assertFalse(scene.hasText(resultText))
                    scene.click("Result")
                    scene.settle { scene.hasText(resultText) && scene.hasText(plain) }
                    assertEquals(resultText, layout(resultText).layoutInput.text.text)
                    assertEquals(FontFamily.Monospace, layout(resultText).layoutInput.style.fontFamily)
                    assertTrue(layout(resultText).layoutInput.softWrap && layout(resultText).lineCount > 1)
                    assertNotEquals(FontFamily.Monospace, layout(plain).layoutInput.style.fontFamily)
                    assertTrue(scene.hasText("Image: [inline image, 12 characters]"))
                    assertFalse(scene.containsHistoryText("BASE64SECRET"))
                    assertFalse(scene.containsHistoryText("META-SECRET"))
                }
            } finally { scene.close() }
        } }
    }
    test("MCP media previews stay redacted for arguments and structured results") { offscreenTest {
        val media = Json.parseToJsonElement("""{"nested":[{"data":"SECRET-DATA"}]}""")
        val event = StableMcpToolEvent("mcp-media", name = "inspect", namespace = "mcp", arguments = media,
            result = CallToolResult(content = emptyList(), structuredContent = media, isError = true))
        val scene = OffscreenScene.create(width = 360) {
            HistoryEventContent(event, rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasDescription("History tool: Failed to run mcp.inspect") }
            scene.clickDescription("History tool: Failed to run mcp.inspect")
            scene.settle { scene.hasText("Arguments") && scene.hasText("Result") }
            scene.click("Arguments")
            scene.settle { scene.hasText("Arguments: [structured data with inline media]") }
            scene.click("Result")
            scene.settle {
                scene.hasText("Arguments: [structured data with inline media]") &&
                    scene.hasText("Structured result: [structured data with inline media]")
            }
            assertFalse(scene.containsHistoryText("SECRET-DATA"))
        } finally { scene.close() }
    } }
    test("MCP primitive and array JSON use the recorded preview while plain text stays plain") { offscreenTest {
        val event = StableMcpToolEvent("mcp-values", name = "inspect", namespace = "mcp", arguments = JsonPrimitive(42),
            result = CallToolResult(content = listOf(Json.parseToJsonElement("[1,2]")),
                structuredContent = JsonPrimitive(false)))
        val scene = OffscreenScene.create(width = 360) {
            HistoryEventContent(event, rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasDescription("History tool: mcp.inspect") }
            scene.clickDescription("History tool: mcp.inspect")
            scene.settle { scene.hasText("Arguments") && scene.hasText("Result") }
            scene.click("Arguments")
            scene.settle { scene.hasText("Arguments: 42") }
            scene.click("Result")
            val values = listOf("Arguments: 42", "Structured result: false", "Content: [1,2]")
            scene.settle { values.all(scene::hasText) }
            values.forEach { value ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
            }
        } finally { scene.close() }
    } }
    test("empty MCP result remains an ordinary no-content message") { offscreenTest {
        val event = StableMcpToolEvent("mcp-empty", name = "inspect", namespace = "mcp",
            arguments = emptyHistoryArguments, result = CallToolResult(content = emptyList()))
        val scene = OffscreenScene.create(width = 360) {
            HistoryEventContent(event, rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasDescription("History tool: mcp.inspect") }
            scene.clickDescription("History tool: mcp.inspect")
            scene.settle { scene.hasText("Result") }
            scene.click("Result")
            scene.settle { scene.hasText("Result: no content") }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(scene.text("Result: no content").config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
            assertNotEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
        } finally { scene.close() }
    } }
    for (status in listOf("completed", "failed")) {
        test("empty server tool search retains status and arbitrary input status=$status") { offscreenTest {
            val event = StableServerToolSearch(
                ResponseItem.ServerToolSearchCall(arguments = Json.parseToJsonElement("""[1,{"flag":false}]""")),
                ResponseItem.ServerToolSearchOutput(status = status, tools = emptyList()),
            )
            val summary = if (status == "failed") "Failed to search available tools" else "Search available tools"
            val scene = OffscreenScene.create(width = 360) {
                HistoryEventContent(event, rememberComposeHistoryContentRenderer())
            }
            try {
                scene.settle { scene.hasDescription("History tool: $summary") }
                scene.clickDescription("History tool: $summary")
                scene.settle { scene.hasText("""server_tool_search; arguments=[1,{"flag":false}]""") }
                val tool = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: $summary") == true
                }
                assertEquals("$status; Expanded", tool.config.getOrNull(SemanticsProperties.StateDescription))
                assertFalse(scene.hasText("Result"))
                assertFalse(scene.hasText("error"))
            } finally { scene.close() }
        } }
    }
    for (width in listOf(180, 360)) for (scale in listOf(1f, 1.5f)) {
        test("server tool search wraps recorded namespace definition width=$width scale=$scale") { offscreenTest {
            val description = "服務 Unicode " + "long-definition ".repeat(24)
            val tools = Json.decodeFromString<List<LoadableToolSpec>>(
                """[{"type":"namespace","name":"cloud","description":"$description","tools":[
                     {"type":"function","name":"first","description":"recorded",
                      "parameters":{"type":"object","properties":{}}},
                     {"type":"function","name":"second","description":"also recorded",
                      "parameters":{"type":"object","properties":{}}}
                   ]},{"type":"function","name":"independent","description":"other",
                      "parameters":{"type":"object","properties":{}}}]""",
            )
            val expected = Json { prettyPrint = true; encodeDefaults = true }
                .encodeToString(LoadableToolSpec.serializer(), tools.first())
            val event = StableServerToolSearch(
                ResponseItem.ServerToolSearchCall(arguments = Json.parseToJsonElement("""{"query":"needle","nested":{"flag":false}}""")),
                ResponseItem.ServerToolSearchOutput(status = "completed", tools = tools),
            )
            val scene = OffscreenScene.create(width = width, height = 3000) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Search available tools") }
                scene.key(Key.Tab)
                scene.key(Key.Enter)
                scene.settle { scene.hasText("cloud.first") && scene.hasText("cloud.second") }
                assertTrue(scene.hasText("""server_tool_search; query="needle"; nested={"flag":false}"""))
                assertFalse(scene.hasText(expected))
                scene.click("cloud.first")
                scene.settle { scene.hasText(expected) }
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(expected).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                assertEquals(expected, layouts.single().layoutInput.text.text)
                assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
                assertTrue(layouts.single().layoutInput.softWrap)
                assertTrue(layouts.single().lineCount > expected.lines().size)
                scene.click("cloud.first")
                scene.settle { !scene.hasText(expected) }
                scene.click("cloud.second")
                scene.settle { scene.hasText(expected) }
            } finally { scene.close() }
        } }
    }
    test("empty local tool search preserves the query without inventing results") { offscreenTest {
        val event = StableToolSearchEvent("empty", arguments = SearchToolCallParams("no match"),
            result = ToolSearchResult.Success(emptyList()))
        val scene = OffscreenScene.create(width = 360) {
            HistoryEventContent(event, rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasDescription("History tool: Search available tools") }
            scene.key(Key.Tab)
            scene.key(Key.Enter)
            scene.settle { scene.hasText("tool_search; query=no match") }
            assertFalse(scene.hasText("Result"))
            scene.key(Key.Enter)
            scene.settle { !scene.hasText("tool_search; query=no match") }
        } finally { scene.close() }
    } }
    for (width in listOf(180, 360)) for (scale in listOf(1f, 1.5f)) {
        test("local tool search definition retains namespace and wraps in monospace width=$width scale=$scale") { offscreenTest {
            val longDescription = "Unicode 漢字 and long content " + "preserved ".repeat(28)
            val tools = Json.decodeFromString<List<LoadableToolSpec>>(
                """[
                  {"type":"namespace","name":"calendar","description":"$longDescription","tools":[
                    {"type":"function","name":"create","description":"first","strict":true,
                     "defer_loading":true,"parameters":{"type":"object","properties":{}}},
                    {"type":"function","name":"delete","description":"second",
                     "parameters":{"type":"object","properties":{}}}
                  ]},
                  {"type":"function","name":"independent","description":"independent body",
                   "parameters":{"type":"object","properties":{}}}
                ]""",
            )
            val expected = Json { prettyPrint = true; encodeDefaults = true }
                .encodeToString(LoadableToolSpec.serializer(), tools.first())
            val event = StableToolSearchEvent("search", arguments = SearchToolCallParams("calendar", 4),
                result = ToolSearchResult.Success(tools))
            val scene = OffscreenScene.create(width = width, height = 3000) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle("outer") { scene.hasDescription("History tool: Search available tools") }
                scene.key(Key.Tab)
                scene.key(Key.Enter)
                scene.settle("definitions listed") { scene.hasText("calendar.create") && scene.hasText("calendar.delete") }
                assertFalse(scene.hasText(expected))
                scene.click("calendar.create")
                scene.settle("first definition open") { scene.hasText(expected) }
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(expected).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                val layout = layouts.single()
                assertEquals(expected, layout.layoutInput.text.text)
                assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
                assertTrue(layout.layoutInput.softWrap)
                assertTrue(layout.lineCount > expected.lines().size, "Long recorded JSON must visually wrap")
                assertFalse(scene.containsHistoryText("independent body"))
                val headingBounds = scene.text("calendar.create").boundsInWindow
                assertTrue(headingBounds.top >= 0f && headingBounds.bottom <= 3000f,
                    "First disclosure must remain within viewport: $headingBounds")
                scene.click("calendar.create")
                repeat(3) { scene.advance(); kotlinx.coroutines.delay(1) }
                assertFalse(scene.hasText(expected), "First disclosure remains open after clicking $headingBounds")
                scene.settle("first definition closed; heading=$headingBounds") { !scene.hasText(expected) }
                scene.click("calendar.delete")
                scene.settle("second definition open") { scene.hasText(expected) }
            } finally { scene.close() }
        } }
    }
    for (dark in listOf(false, true)) for (invalid in listOf(false, true)) {
        test("local tool search failure uses recorded theme error dark=$dark invalid=$invalid") { offscreenTest {
            val message = "  preserved error\n  details  "
            val result = if (invalid) ToolSearchResult.InvalidArguments(message)
                else ToolSearchResult.ExecutionFailure(message)
            val event = StableToolSearchEvent("search-error", arguments = SearchToolCallParams("my query", 3),
                result = result)
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val text = "error: $message"
            val scene = OffscreenScene.create(width = 360) {
                MaterialTheme(colorScheme = scheme) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Failed to search available tools") }
                scene.key(Key.Tab)
                scene.key(Key.Enter)
                scene.settle { scene.hasText(text) }
                assertTrue(scene.hasText("tool_search; query=my query; limit=3"))
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(text).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                assertEquals(text, layouts.single().layoutInput.text.text)
                assertEquals(scheme.error, layouts.single().layoutInput.style.color)
            } finally { scene.close() }
        } }
    }
    for (dark in listOf(false, true)) for (failure in listOf(false, true)) for (empty in listOf(false, true)) {
        test("web.run result preserves operations and recorded text dark=$dark failure=$failure empty=$empty") { offscreenTest {
            val value = if (empty) "" else "  recorded [value]\n  second line  "
            val result = if (failure) StableWebSearchResult.Failure(value)
                else StableWebSearchResult.Success(SearchResponse(output = value, encryptedOutput = "PRIVATE-SIDE-CHANNEL"))
            val event = StableWebSearchToolEvent(
                "web-fake", commands = SearchCommands(
                    searchQuery = listOf(SearchQuery("alpha"), SearchQuery("beta")),
                    open = listOf(OpenOperation("ref", 3)),
                ), result = result,
            )
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val summary = if (failure) "Failed to search the web: alpha" else "Search the web: alpha"
            val text = "${if (failure) "error" else "output"}: $value"
            val scene = OffscreenScene.create(width = 360) {
                MaterialTheme(colorScheme = scheme) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: $summary") }
                assertFalse(scene.hasText(text))
                scene.key(Key.Tab)
                scene.key(Key.Enter)
                scene.settle { scene.hasText(text) }
                val metadataLayouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text("web.run").config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(metadataLayouts))
                assertEquals(scheme.onSurfaceVariant, metadataLayouts.single().layoutInput.style.color)
                assertTrue(scene.hasText("Search alpha"))
                assertTrue(scene.hasText("Search beta"))
                assertTrue(scene.hasText("Open ref at line 3"))
                assertFalse(scene.hasText("PRIVATE-SIDE-CHANNEL"))
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(text).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                assertEquals(text, layouts.single().layoutInput.text.text)
                assertEquals(if (failure) scheme.error else scheme.onSurface, layouts.single().layoutInput.style.color)
                scene.key(Key.Enter)
                scene.settle { !scene.hasText(text) }
            } finally { scene.close() }
        } }
    }
    for (dark in listOf(false, true)) for (pending in listOf(false, true)) for (kind in 0..1) {
        test("external invalid recorded error keeps nested disclosure and theme color dark=$dark pending=$pending kind=$kind") { offscreenTest {
            val message = "  External failure [preserve]\n  second line  "
            val raw = "  RAW [arguments]\n  trailing  "
            val stableInput = if (kind == 0) InvalidToolInvocation.Function("external_function", arguments = raw)
                else InvalidToolInvocation.Custom("external_custom", input = raw)
            val pendingInput = if (kind == 0) PendingInvalidToolInvocation.Function("external_function", arguments = raw)
                else PendingInvalidToolInvocation.Custom("external_custom", input = raw)
            val expectedInput = "${if (kind == 0) "Arguments" else "Input"}: $raw"
            val errorText = "Error: $message"
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = 360) {
                MaterialTheme(colorScheme = scheme) {
                    val renderer = rememberComposeHistoryContentRenderer()
                    if (pending) HistoryEventContent(
                        PendingInvalidToolCall("pending-external", invocation = pendingInput, message = message), renderer,
                    ) else HistoryEventContent(
                        StableInvalidToolCall("stable-external", invocation = stableInput, message = message), renderer,
                    )
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Model emitted an invalid tool call") }
                scene.key(Key.Tab)
                scene.key(Key.Enter)
                scene.settle { scene.hasText("Invocation") && scene.hasText("Error") }
                assertFalse(scene.hasText(errorText))
                scene.click("Invocation")
                scene.click("Error")
                scene.settle { scene.hasText(expectedInput) && scene.hasText(errorText) }
                fun layout(value: String): TextLayoutResult {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    return layouts.single()
                }
                assertEquals(scheme.error, layout(errorText).layoutInput.style.color)
                assertEquals(if (pending) scheme.onSurfaceVariant else scheme.onSurface,
                    layout(expectedInput).layoutInput.style.color)
                assertEquals(errorText, layout(errorText).layoutInput.text.text)
            } finally { scene.close() }
        } }
    }
    for (dark in listOf(false, true)) for (pending in listOf(false, true)) for (kind in 0..2) {
        test("internal invalid recorded error uses theme color dark=$dark pending=$pending kind=$kind") { offscreenTest {
            val message = "  Bad input [preserve]\n  second line  "
            val json = Json.parseToJsonElement("""{"query":[1,2],"private":"recorded"}""")
            val stableInput = when (kind) {
                0 -> InvalidToolInvocation.Function("exec_command", arguments = "{BROKEN")
                1 -> InvalidToolInvocation.Custom("apply_patch", input = "  RAW\n  INPUT")
                else -> InvalidToolInvocation.ToolSearch(json)
            }
            val pendingInput = when (kind) {
                0 -> PendingInvalidToolInvocation.Function("exec_command", arguments = "{BROKEN")
                1 -> PendingInvalidToolInvocation.Custom("apply_patch", input = "  RAW\n  INPUT")
                else -> PendingInvalidToolInvocation.ToolSearch(json)
            }
            val expectedInput = when (kind) {
                0 -> "arguments: {BROKEN"
                1 -> "input:   RAW\n  INPUT"
                else -> "arguments: $json"
            }
            val errorText = "error: $message"
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = 360) {
                MaterialTheme(colorScheme = scheme) {
                    val renderer = rememberComposeHistoryContentRenderer()
                    if (pending) HistoryEventContent(
                        PendingInvalidToolCall("pending-invalid", invocation = pendingInput, message = message), renderer,
                    ) else HistoryEventContent(
                        StableInvalidToolCall("stable-invalid", invocation = stableInput, message = message), renderer,
                    )
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Model emitted an invalid tool call") }
                scene.key(Key.Tab)
                scene.key(Key.Enter)
                scene.settle { scene.hasText(errorText) && scene.hasText(expectedInput) }
                fun layout(value: String): TextLayoutResult {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    return layouts.single()
                }
                assertEquals(scheme.error, layout(errorText).layoutInput.style.color)
                assertEquals(scheme.onSurface, layout(expectedInput).layoutInput.style.color)
                assertEquals(errorText, layout(errorText).layoutInput.text.text)
                assertFalse(scene.hasText("Invocation"))
                assertFalse(scene.hasText("Error"))
            } finally { scene.close() }
        } }
    }
    for (width in listOf(160, 360)) for (scale in listOf(1f, 1.5f)) {
        for (status in listOf("succeeded", "failed", "running")) {
            test("tool text disclosure aligns without changing semantics width=$width scale=$scale status=$status") { offscreenTest {
                val title = "Long tool title with spaces " + "detail ".repeat(12)
                val scene = OffscreenScene.create(width = width) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        val renderer = rememberComposeHistoryContentRenderer()
                        renderer.Tool(title, "fixture_tool", status, "tool-mark", HistoryTextStyle.Normal, null) { details ->
                            details.Section("Arguments") { details.Text("Full private fixture details", HistoryTextStyle.Normal) }
                        }
                    }
                }
                fun marks(state: String) = scene.nodes().filter {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "history-tool-disclosure-$state"
                }
                fun tool() = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: $title") == true
                }
                try {
                    scene.settle { scene.hasDescription("History tool: $title") }
                    assertEquals(1, marks("Collapsed").size, "Collapsed tool must have a text mark")
                    assertEquals("$status; Collapsed", tool().config.getOrNull(SemanticsProperties.StateDescription))
                    assertFalse(scene.hasText("> $title"))
                    val mark = marks("Collapsed").single().boundsInRoot
                    val heading = scene.text(title).boundsInRoot
                    assertTrue(mark.width > 0f && mark.right <= heading.left)
                    assertTrue(abs(mark.top - heading.top) <= 1f)
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(title).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    assertTrue(layouts.single().lineCount > 1)
                    assertEquals(layouts.single().getLineLeft(0), layouts.single().getLineLeft(1))
                    scene.key(Key.Tab)
                    scene.settle { tool().config.getOrNull(SemanticsProperties.Focused) == true }
                    scene.key(Key.Enter)
                    scene.settle { marks("Expanded").size == 1 && scene.hasText("Arguments") }
                    assertEquals("$status; Expanded", tool().config.getOrNull(SemanticsProperties.StateDescription))
                    assertEquals(1, marks("Collapsed").size)
                    scene.click("Arguments")
                    scene.settle { marks("Expanded").size == 2 && scene.hasText("Full private fixture details") }
                } finally { scene.close() }
            } }
        }
    }
    test("compact tool disclosure preserves full content and exposes status without duplicate visible text") { offscreenTest {
        val scene = OffscreenScene.create(width = 480) {
            val renderer = rememberComposeHistoryContentRenderer()
            renderer.Tool("Compact tool", null, "failed", "density", HistoryTextStyle.Normal, null) {
                renderer.Text("Complete recorded detail", HistoryTextStyle.Normal)
            }
            TextButton({}, Modifier.fillMaxWidth()) { Text("Baseline") }
        }
        try {
            scene.settle { scene.hasDescription("History tool: Compact tool") && scene.hasText("Baseline") }
            fun tool() = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: Compact tool") == true
            }
            val baseline = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Baseline" } == true }
            }
            assertTrue(tool().boundsInRoot.height < baseline.boundsInRoot.height)
            assertEquals("failed; Collapsed", tool().config.getOrNull(SemanticsProperties.StateDescription))
            scene.clickDescription("History tool: Compact tool")
            scene.settle { scene.hasText("Complete recorded detail") }
            assertEquals("failed; Expanded", tool().config.getOrNull(SemanticsProperties.StateDescription))
            assertFalse(scene.hasText("failed"))
        } finally { scene.close() }
    } }

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
                if (index == 3) assertFalse(scene.hasText("Process"))
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
            scene.settle { scene.hasText("Result") }
            assertFalse(scene.hasText("Result: [structured data with inline media]"))
            scene.click("Result")
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
            scene.settle { scene.hasText("Result") }
            scene.click("Result")
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
                assertFalse(scene.hasText("Arguments"))
                if (index == 1) {
                    assertTrue(scene.hasText("Search first\nSearch second\nSearch third"))
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
            assertFalse(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("history-tool-disclosure-") == true
            }, "Reasoning must retain its own indicator")
            scene.frame()
        } finally { scene.close() }
    } }

    for (width in listOf(180, 800)) for (scale in listOf(1f, 1.5f))
        for (dark in listOf(false, true)) for (status in listOf("running", "completed", "failed")) {
        test("patch actual typography roles width=$width scale=$scale dark=$dark status=$status") { offscreenTest {
            val path = "目录/" + "long-path/".repeat(20) + "漢字-café.txt"
            val diff = Patch("", listOf(UpdateFileHunk(path, chunks = listOf(
                UpdateFileChunk(oldLines = listOf("old α"), newLines = listOf("new 漢字")),
            ))), workdir = "/workspace/目录", environmentId = "环境")
            val stable = StablePatchToolEvent("styles", diff = diff, result =
                if (status == "failed") StablePatchToolExecutionResult.Failure("context 不匹配")
                else StablePatchToolExecutionResult.Success(PatchApplyResult(
                    PatchAffectedPaths(emptyList(), listOf(path), emptyList()))))
            val presentation = if (status == "running") diff.toPendingPatchPresentation()
                else stable.toStablePatchPresentation()
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            var bodyFamily: FontFamily? = null
            val scene = OffscreenScene.create(width = width, height = 2400) {
                UiTheme {
                    MaterialTheme(colorScheme = scheme) {
                        bodyFamily = MaterialTheme.typography.bodyMedium.fontFamily
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                val renderer = rememberComposeHistoryContentRenderer()
                                if (status == "running") HistoryEventContent(PendingPatchToolEvent("styles", diff = diff), renderer)
                                else HistoryEventContent(stable, renderer)
                            }
                        }
                    }
                }
            }
            try {
                fun layout(value: String): TextLayoutResult {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    return layouts.single()
                }
                fun check(value: String, size: Int, height: Int, weight: FontWeight,
                          mono: Boolean, color: androidx.compose.ui.graphics.Color) {
                    val actual = layout(value).layoutInput.style
                    assertEquals(size.sp, actual.fontSize, value)
                    assertEquals(height.sp, actual.lineHeight, value)
                    assertEquals(weight, actual.fontWeight, value)
                    assertEquals(if (mono) FontFamily.Monospace else bodyFamily, actual.fontFamily, value)
                    assertEquals(color, actual.color, value)
                }
                scene.settle { scene.hasDescription("History tool: ${presentation.header}") }
                val titleColor = when (status) {
                    "running" -> scheme.tertiary
                    "failed" -> scheme.error
                    else -> scheme.onSurface
                }
                check(presentation.header, 16, 24, FontWeight.SemiBold, false, titleColor)
                val collapsed = layout(presentation.header).layoutInput.style
                scene.clickDescription("History tool: ${presentation.header}")
                scene.settle { scene.hasText("apply_patch") }
                assertEquals(collapsed, layout(presentation.header).layoutInput.style)
                check("apply_patch", 14, 20, FontWeight.Normal, false, scheme.onSurfaceVariant)
                for (line in presentation.lines) {
                    val metadata = line.kind == PatchPresentationLineKind.Metadata
                    val mono = line.kind in listOf(PatchPresentationLineKind.File, PatchPresentationLineKind.Context,
                        PatchPresentationLineKind.Addition, PatchPresentationLineKind.Removal)
                    val color = when (line.kind) {
                        PatchPresentationLineKind.File -> scheme.onSurface
                        PatchPresentationLineKind.Addition -> scheme.tertiary
                        PatchPresentationLineKind.Removal, PatchPresentationLineKind.Failure -> scheme.error
                        else -> scheme.onSurfaceVariant
                    }
                    check(line.text, if (metadata) 14 else 16, if (metadata) 20 else 24,
                        if (line.kind == PatchPresentationLineKind.File) FontWeight.Bold else FontWeight.Normal, mono, color)
                    assertEquals(line.text, layout(line.text).layoutInput.text.text)
                    assertTrue(layout(line.text).layoutInput.softWrap)
                    assertFalse(layout(line.text).hasVisualOverflow)
                }
                assertTrue(layout("M $path").lineCount > 1, "Long path must wrap at width=$width scale=$scale")
            } finally { scene.close() }
        } }
    }

    test("patch keeps a bounded first page and explicit show-more retains original line order") { offscreenTest {
        val diff = Patch(patch = "", hunks = listOf(AddFileHunk("new.txt", (0..430).joinToString("\n") { "line $it" })))
        val event = PendingPatchToolEvent("patch", diff = diff)
        var buttonColor = androidx.compose.ui.graphics.Color.Unspecified
        val scene = OffscreenScene.create {
            UiTheme {
                buttonColor = MaterialTheme.colorScheme.primary
                HistoryEventContent(event, rememberComposeHistoryContentRenderer())
            }
        }
        try {
            fun checkPageLabel(label: String) {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(label).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                val style = layouts.single().layoutInput.style
                assertEquals(14.sp, style.fontSize)
                assertEquals(20.sp, style.lineHeight)
                assertEquals(FontWeight.Medium, style.fontWeight)
                assertNotEquals(FontFamily.Monospace, style.fontFamily)
                assertEquals(buttonColor, style.color)
            }
            scene.settle { scene.hasDescription("History tool: Editing new.txt") }
            scene.clickDescription("History tool: Editing new.txt")
            scene.settle { scene.hasText("+ line 198") }
            assertFalse(scene.hasText("+ line 199"))
            val showMore = scene.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                    ?.any { it.startsWith("Show next ") && it.endsWith(" remaining") } == true }
            checkPageLabel("Show next 200 lines 232 remaining")
            assertTrue(assertNotNull(showMore.config[SemanticsActions.OnClick].action).invoke())
            scene.settle { scene.hasText("+ line 398") }
            assertFalse(scene.hasText("+ line 399"))
            val lastPage = scene.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it.startsWith("Show next ") && it.endsWith(" remaining") } == true }
            checkPageLabel("Show next 32 lines 32 remaining")
            assertTrue(assertNotNull(lastPage.config[SemanticsActions.OnClick].action).invoke())
            scene.settle { scene.hasText("+ line 430") }
            for (number in 0..430) {
                assertEquals(1, scene.nodes().count { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "+ line $number" } == true
                })
            }
            assertEquals((0..430).map { "+ line $it" }, scene.nodes().flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
            }.filter { it.startsWith("+ line ") })
            assertFalse(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value ->
                    value.startsWith("Show next ") && value.endsWith(" remaining")
                } == true
            })
            scene.frame()
        } finally { scene.close() }
    } }

    for (width in listOf(160, 360)) for (scale in listOf(1f, 1.5f)) {
        test("patch diff wraps as monospace without changing its text width=$width scale=$scale") { offscreenTest {
            val raw = "  前缀 café " + "columns ".repeat(20)
            val line = "+ $raw"
            val diff = Patch(patch = "", hunks = listOf(AddFileHunk("unicode.txt", raw)))
            val event = PendingPatchToolEvent("patch", diff = diff)
            val scene = OffscreenScene.create(width = width, height = 1800) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Editing unicode.txt") }
                scene.clickDescription("History tool: Editing unicode.txt")
                scene.settle { scene.hasText(line) }
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(scene.text(line).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                val layout = layouts.single()
                assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
                assertTrue(layout.lineCount > 1)
                assertFalse(layout.hasVisualOverflow)
                assertEquals(line, layout.layoutInput.text.text)
            } finally { scene.close() }
        } }
    }

    test("short patch viewport scrolls to the full wrapped diff and back to its title") { offscreenTest {
        val raw = "  前缀 café " + "columns ".repeat(20)
        val line = "+ $raw"
        val event = PendingPatchToolEvent("patch-short",
            diff = Patch("", listOf(AddFileHunk("unicode.txt", raw))))
        lateinit var scroll: androidx.compose.foundation.ScrollState
        val scene = OffscreenScene.create(width = 160, height = 240) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    HistoryEventContent(event, rememberComposeHistoryContentRenderer())
                }
            }
        }
        try {
            scene.settle { scene.hasDescription("History tool: Editing unicode.txt") }
            scene.clickDescription("History tool: Editing unicode.txt")
            scene.settle { scene.hasText(line) && scroll.maxValue > 0 }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(scene.text(line).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
            assertEquals(line, layouts.single().layoutInput.text.text)
            assertFalse(layouts.single().hasVisualOverflow)
            scroll.scrollTo(scroll.maxValue)
            scene.settle { scroll.value == scroll.maxValue }
            assertTrue(scene.text(line).boundsInRoot.bottom <= 240f)
            scroll.scrollTo(0)
            scene.settle { scroll.value == 0 }
            assertTrue(scene.hasDescription("History tool: Editing unicode.txt"))
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
            scene.settle { scene.hasText("Input") }
            assertFalse(scene.hasText("Input: {\"command\":\"pwd\",\"tty\":true}"))
            scene.click("Input")
            scene.settle { scene.hasText("Input: {\"command\":\"pwd\",\"tty\":true}") }
            scene.frame()
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.containsHistoryText(fragment: String): Boolean =
    nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { fragment in it.text } }
