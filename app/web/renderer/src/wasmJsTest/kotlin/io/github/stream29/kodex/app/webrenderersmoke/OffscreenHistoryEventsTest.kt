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
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventOutput
import io.github.stream29.kodex.tool.readmedia.*
import io.github.stream29.kodex.tool.unifiedexec.*
import io.github.stream29.kodex.utils.applypatch.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.math.abs
import kotlin.time.Instant

private val emptyHistoryArguments = JsonObject(emptyMap())

val offscreenHistoryEventsTest by testSuite {
    for (width in listOf(180, 360)) {
        test("generic JSON details use monospace and wrap width=$width") { offscreenTest {
            val arguments = JsonObject(mapOf("query" to JsonPrimitive("Unicode 漢字 " + "detail ".repeat(30))))
            val result = JsonArray(listOf(JsonPrimitive(42), JsonPrimitive(false)))
            val event = StableJsonTool("generic-json", name = "external", arguments = arguments, result = result)
            val scene = OffscreenScene.create(width = width, height = 1800) {
                HistoryEventContent(event, rememberComposeHistoryContentRenderer())
            }
            try {
                scene.settle { scene.hasDescription("History tool: external") }
                scene.clickDescription("History tool: external")
                scene.settle { scene.hasText("Arguments") && scene.hasText("Result") }
                scene.click("Arguments")
                scene.settle { scene.hasText("Arguments: $arguments") }
                scene.click("Result")
                scene.settle { scene.hasText("Result: $result") }
                for (text in listOf("Arguments: $arguments", "Result: $result")) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(text).config.getOrNull(
                        SemanticsActions.GetTextLayoutResult,
                    )?.action)(layouts))
                    val layout = layouts.single()
                    assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
                    assertEquals(text, layout.layoutInput.text.text)
                    assertFalse(layout.hasVisualOverflow)
                    if (text.startsWith("Arguments:")) assertTrue(layout.lineCount > 1)
                }
            } finally { scene.close() }
        } }
    }
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
                scene.settle { scene.hasText("Outside heading") }
                check("Outside heading", FontWeight.Bold)
                check("Worked for 2s", FontWeight.Normal)
                scene.clickDescription("History tool: Tool heading")
                scene.settle { scene.hasText("Section heading") }
                check("Section heading", FontWeight.Normal)
                scene.click("Section heading")
                scene.settle { scene.hasText("Section body") }
                check("Section body", FontWeight.Normal)
            } finally { scene.close() }
        } }
    }

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
            val json = Json.parseToJsonElement("""{"query":[1,2],"private":"recorded"}""").toString()
            val stableInput = when (kind) {
                0 -> InvalidToolInvocation.Function("exec_command", arguments = "{BROKEN")
                1 -> InvalidToolInvocation.Custom("apply_patch", input = "  RAW\n  INPUT")
                else -> InvalidToolInvocation.Function("tool_search", arguments = json)
            }
            val pendingInput = when (kind) {
                0 -> PendingInvalidToolInvocation.Function("exec_command", arguments = "{BROKEN")
                1 -> PendingInvalidToolInvocation.Custom("apply_patch", input = "  RAW\n  INPUT")
                else -> PendingInvalidToolInvocation.Function("tool_search", arguments = json)
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
        Triple(PendingFunctionToolCall("function", name = "external", arguments = emptyHistoryArguments),
            "Running external", "Tool: external"),
        Triple(PendingCustomToolCall("custom", name = "external", input = "raw input"),
            "Running external", "Tool: external"),
        Triple(PendingCommandExecution("exec", action = PendingCommandExecutionAction.ExecCommand(ExecCommandArguments("pwd", yieldTimeMillis = 10_000))),
            "Running: pwd", "Tool: exec_command"),
        Triple(PendingCommandExecution("poll", action = PendingCommandExecutionAction.WriteStdin(WriteStdinArguments(77, yieldTimeMillis = 250))),
            "Waiting for terminal session 77", "Tool: write_stdin"),
        Triple(PendingReadMedia("media", arguments = ReadMediaToolArguments("/image.png")),
            "Reading media: /image.png", "Tool: read_media"),
        Triple(PendingAwait("await", arguments = AwaitEventArguments(listOf(3, 5))),
            "Waiting for events (terminals: 3, 5)", "Tool: await_event"),
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
        val event = StableJsonTool("json", name = "external", arguments = emptyHistoryArguments,
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

    val recorded = listOf(
        Triple<AgentCommit, String, String>(
            StableImageGenerationCall(ResponseItem.ImageGenerationCall(
                status = "completed", result = "BASE64SECRET", revisedPrompt = "revised prompt",
            )),
            "Generate image", "revised_prompt: revised prompt",
        ),
        Triple(
            StableWebSearchCall(ResponseItem.WebSearchCall(action = WebSearchAction.Search("first", listOf("second", "third")))),
            "Search first", "hosted_web_search",
        ),
        Triple(
            StableAwait("await", arguments = AwaitEventArguments(listOf(7)),
                result = StableAwaitEventResult.Output(AwaitEventOutput(Instant.fromEpochMilliseconds(0)))),
            "Wait for events", "timestamp: 1970-01-01T00:00:00Z",
        ),
        Triple(
            StableTextTool("utility", name = "get_context_remaining", arguments = emptyHistoryArguments,
                result = "Original returned sentence.", success = true),
            "Check remaining context", "Original returned sentence.",
        ),
        Triple(
            StableReadMedia("media", arguments = ReadMediaToolArguments("/image.png", MediaDetail.Original),
                result = StableReadMediaResult.Success(ReadMediaToolOutput("data:image/png;base64,SECRET", MediaDetail.High))),
            "Read media: /image.png", "output_detail: high",
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

    for (width in listOf(180, 800)) for (scale in listOf(1f, 1.5f))
        for (dark in listOf(false, true)) for (status in listOf("running", "completed", "failed")) {
        test("patch actual typography roles width=$width scale=$scale dark=$dark status=$status") { offscreenTest {
            val path = "目录/" + "long-path/".repeat(20) + "漢字-café.txt"
            val diff = Patch("", listOf(UpdateFileHunk(path, chunks = listOf(
                UpdateFileChunk(oldLines = listOf("old α"), newLines = listOf("new 漢字")),
            ))), workdir = "/workspace/目录", environmentId = "环境")
            val stable = StablePatch("styles", diff = diff, result =
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
                                if (status == "running") HistoryEventContent(PendingPatch("styles", diff = diff), renderer)
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
        val event = PendingPatch("patch", diff = diff)
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
            val event = PendingPatch("patch", diff = diff)
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
        val event = PendingPatch("patch-short",
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
