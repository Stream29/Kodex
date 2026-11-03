package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.app.history.contract.item.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryItemRenderer
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

private fun OffscreenScene.hierarchyLayout(value: String): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    assertTrue(assertNotNull(text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
    return layouts.single()
}

val offscreenHistoryToolHierarchyTest by testSuite {
    for (width in listOf(180, 360)) for (failed in listOf(false, true)) {
        test("collapsed command stays one visual line and expanded command remains complete width=$width failed=$failed") { offscreenTest {
            val preview = "printf " + "long-command-".repeat(12)
            val command = "$preview\nprintf 'second line'"
            val summary = "${if (failed) "Failed to run" else "Run"}: $preview"
            val event = StableCommandExecutionToolEvent(
                "one-line", action = StableCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(command, yieldTimeMillis = 1000)),
                result = if (failed) StableCommandExecutionResult.Failure("fixture error")
                else StableCommandExecutionResult.Output(UnifiedExecOutput("chunk", 0.0, 0, originalTokenCount = 1, output = "ok")),
            )
            val header = ToolHistoryItemHeader.CommandExecution(
                CommandExecutionHistoryAction.Run(preview),
                if (failed) CommandExecutionHistoryResult.Failure else CommandExecutionHistoryResult.Output(0, null),
                0.milliseconds,
            )
            val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
            val item = object : ToolHistoryItemViewModel {
                override val index = 1
                override val state = states
                override fun expand() { states.value = ToolHistoryItemState.Expanded(header, event) }
                override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
            }
            val scene = OffscreenScene.create(width = width, height = 1000) {
                MaterialTheme { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
            }
            try {
                scene.settle { scene.hasDescription("Expand History item: $summary") }
                assertEquals(1, scene.hierarchyLayout(summary).lineCount)
                scene.clickDescription("Expand History item: $summary")
                val full = "Run: $command"
                scene.settle { scene.hasDescription("History tool: $full") }
                assertEquals(full, scene.hierarchyLayout(full).layoutInput.text.text)
                assertTrue(scene.hierarchyLayout(full).lineCount > 1)
                scene.clickDescription("History tool: $full")
                scene.settle { scene.hasDescription("Expand History item: $summary") }
                assertEquals(1, scene.hierarchyLayout(summary).lineCount)
            } finally { scene.close() }
        } }
    }
    for (width in listOf(180, 360)) {
        test("running command stays one visual line when collapsed width=$width") { offscreenTest {
            val command = "printf " + "long-command-".repeat(12)
            val summary = "Running: $command"
            val event = PendingCommandExecutionToolEvent(
                "running-one-line", action = PendingCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(command, yieldTimeMillis = 1000)),
            )
            val scene = OffscreenScene.create(width = width, height = 1000) {
                MaterialTheme { HistoryEventContent(event, rememberComposeHistoryItemRenderer()) }
            }
            try {
                scene.settle { scene.hasDescription("History tool: $summary") }
                assertEquals(1, scene.hierarchyLayout(summary).lineCount)
            } finally { scene.close() }
        } }
    }
    for (width in listOf(180, 360)) for (dark in listOf(false, true)) for (failed in listOf(false, true)) {
        test("ordinary stored tool title and detail hierarchy width=$width dark=$dark failed=$failed") { offscreenTest {
            val summary = if (failed) "Failed to run external" else "external"
            val status = if (failed) "failed" else "succeeded"
            val event = StableTextToolEvent("hierarchy", name = "external", arguments = JsonObject(emptyMap()),
                result = "full result\n  retained", success = !failed)
            val header = ToolHistoryItemHeader.Summary(summary, status, 0.milliseconds)
            val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
            val item = object : ToolHistoryItemViewModel {
                override val index = 1
                override val state = states
                override fun expand() { states.value = ToolHistoryItemState.Expanded(header, event) }
                override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
            }
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = width, height = 1000) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    MaterialTheme(colorScheme = scheme) {
                        val renderer = rememberComposeHistoryItemRenderer()
                        renderer.Text("Body reference", HistoryTextStyle.Normal)
                        HistoryItemContent(item, renderer)
                    }
                }
            }
            try {
                scene.settle { scene.hasDescription("Expand History item: $summary") }
                val body = scene.hierarchyLayout("Body reference").layoutInput.style
                val collapsed = scene.hierarchyLayout(summary).layoutInput.style
                assertTrue(collapsed.fontSize.value >= body.fontSize.value)
                assertTrue((collapsed.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight)
                assertEquals(if (failed) scheme.error else scheme.onSurface, collapsed.color)
                val node = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: $summary") == true
                }
                assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                scene.key(Key.Enter)
                scene.settle { scene.hasText("Tool: external") && scene.hasText("Result") }
                val expanded = scene.hierarchyLayout(summary).layoutInput.style
                assertEquals(collapsed.fontSize, expanded.fontSize)
                assertEquals(collapsed.fontWeight, expanded.fontWeight)
                assertEquals(collapsed.color, expanded.color)
                assertEquals(scheme.onSurfaceVariant, scene.hierarchyLayout("Tool: external").layoutInput.style.color)
                scene.click("Result")
                val result = "Result: full result\n  retained"
                scene.settle { scene.hasText(result) }
                assertEquals(scheme.onSurface, scene.hierarchyLayout(result).layoutInput.style.color)
                assertEquals(result, scene.hierarchyLayout(result).layoutInput.text.text)
            } finally { scene.close() }
        } }
    }
    for (dark in listOf(false, true)) {
        test("command metadata is secondary and recorded output stays primary dark=$dark") { offscreenTest {
            val event = StableCommandExecutionToolEvent(
                "command-hierarchy",
                action = StableCommandExecutionAction.ExecCommand(
                    ExecCommandArguments("printf hello", workdir = "/fixture", yieldTimeMillis = 10_000)),
                result = StableCommandExecutionResult.Output(
                    UnifiedExecOutput("fake", 0.0, exitCode = 0, originalTokenCount = 2, output = "raw\n  output")),
            )
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = 360) {
                MaterialTheme(colorScheme = scheme) {
                    HistoryEventContent(event, rememberComposeHistoryItemRenderer())
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Run: printf hello") }
                scene.clickDescription("History tool: Run: printf hello")
                val metadata = "exec_command; cwd=/fixture; yield_time_ms=10000"
                val outputHeader = "output(exit_code=0; wall_time=0.0s; original_token_count=2):"
                scene.settle { scene.hasText(metadata) && scene.hasText("raw\n  output") }
                assertEquals(scheme.onSurfaceVariant, scene.hierarchyLayout(metadata).layoutInput.style.color)
                assertEquals(scheme.onSurfaceVariant, scene.hierarchyLayout(outputHeader).layoutInput.style.color)
                assertEquals(scheme.onSurface, scene.hierarchyLayout("raw\n  output").layoutInput.style.color)
                assertEquals(FontFamily.Monospace, scene.hierarchyLayout("raw\n  output").layoutInput.style.fontFamily)
            } finally { scene.close() }
        } }
        test("running title and image failure preserve status and error semantics dark=$dark") { offscreenTest {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = 360, height = 1000) {
                MaterialTheme(colorScheme = scheme) {
                    val renderer = rememberComposeHistoryItemRenderer()
                    renderer.Text("Body reference", HistoryTextStyle.Normal)
                    HistoryEventContent(PendingFunctionToolEvent("running", name = "external", arguments = JsonObject(emptyMap())), renderer)
                    HistoryEventContent(StableImageGenerationToolEvent("image", arguments = ImageGenToolArguments("prompt"),
                        result = StableImageGenerationResult.Failure("fixture failed")), renderer)
                }
            }
            try {
                scene.settle { scene.hasDescription("History tool: Running external") && scene.hasDescription("History tool: Generate image") }
                val title = scene.hierarchyLayout("Running external").layoutInput.style
                assertTrue(title.fontSize.value >= scene.hierarchyLayout("Body reference").layoutInput.style.fontSize.value)
                assertTrue((title.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight)
                assertEquals(scheme.tertiary, title.color)
                scene.clickDescription("History tool: Running external")
                scene.settle { scene.hasText("Tool: external") }
                assertEquals(scheme.onSurfaceVariant, scene.hierarchyLayout("Tool: external").layoutInput.style.color)
                scene.clickDescription("History tool: Generate image")
                scene.settle { scene.hasText("error: fixture failed") }
                assertEquals(scheme.onSurfaceVariant, scene.hierarchyLayout("image_gen.imagegen").layoutInput.style.color)
                assertEquals(scheme.onSurface, scene.hierarchyLayout("prompt: prompt").layoutInput.style.color)
                assertEquals(scheme.onSurface, scene.hierarchyLayout("error: fixture failed").layoutInput.style.color)
            } finally { scene.close() }
        } }
    }
}
