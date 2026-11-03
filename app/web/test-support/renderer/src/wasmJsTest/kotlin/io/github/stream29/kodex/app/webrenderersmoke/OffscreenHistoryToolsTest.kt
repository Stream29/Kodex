package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryContentRenderer
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.unifiedexec.*
import kotlin.test.*
import kotlin.time.Duration.Companion.microseconds

val offscreenHistoryToolsTest by testSuite {
    for (width in listOf(160, 360)) for (scale in listOf(1f, 1.5f)) {
        test("Plan status columns stay aligned and compact width=$width scale=$scale") { offscreenTest {
            val longStep = "Multi-line plan " + "Unicode 步骤 ".repeat(3)
            val event = StablePlanUpdate("plan", arguments = UpdatePlanArgs(null, listOf(
                PlanItemArg("FIRST_PLAN_STEP", StepStatus.Completed),
                PlanItemArg(longStep, StepStatus.InProgress),
                PlanItemArg("FINAL_PLAN_STEP", StepStatus.Pending),
            )))
            val scene = OffscreenScene.create(width = width, height = 2000) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HistoryPlanContent(event, rememberComposeHistoryContentRenderer())
                }
            }
            try {
                scene.settle { scene.hasText("FINAL_PLAN_STEP") }
                val heading = scene.text("Update Plan").boundsInRoot
                val first = scene.text("FIRST_PLAN_STEP").boundsInRoot
                val middle = scene.text(longStep).boundsInRoot
                val last = scene.text("FINAL_PLAN_STEP").boundsInRoot
                assertTrue(first.left > heading.left)
                assertEquals(first.left, middle.left)
                assertEquals(first.left, last.left)
                assertEquals(first.bottom, middle.top)
                assertEquals(middle.bottom, last.top)
                assertTrue(scene.hasText("x"))
                assertTrue(scene.hasText(">"))
                assertFalse(scene.hasText("[x]"))
                val statuses = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
                assertEquals(listOf("Completed", "In progress", "Pending"),
                    statuses.filter { it in listOf("Completed", "In progress", "Pending") })
                fun layout(value: String): TextLayoutResult {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    return layouts.single()
                }
                val middleLayout = layout(longStep)
                assertTrue(middleLayout.lineCount > 1)
                assertFalse(middleLayout.hasVisualOverflow)
            } finally { scene.close() }
        } }
    }
    test("shared Plan shows ordered read-only steps and status semantics with elapsed time") { offscreenTest {
        val event = StablePlanUpdate("plan", arguments = UpdatePlanArgs("  Explanation  ", listOf(
            PlanItemArg("First", StepStatus.Completed), PlanItemArg("Second", StepStatus.InProgress), PlanItemArg("Third", StepStatus.Pending),
        )))
        val scene = OffscreenScene.create { HistoryPlanContent(event, rememberComposeHistoryContentRenderer(1_500.microseconds)) }
        try {
            scene.settle { scene.hasText("Update Plan") && scene.hasText(" +2ms") }
            val rows = listOf("Explanation", "First", "Second", "Third")
            assertTrue(rows.all(scene::hasText))
            assertTrue(scene.text("Explanation").boundsInRoot.left > scene.text("Update Plan").boundsInRoot.left)
            assertEquals(rows, rows.sortedBy { scene.text(it).boundsInWindow.top })
            val statuses = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.StateDescription) in listOf("Completed", "In progress", "Pending")
            }
            assertEquals(listOf("Completed", "In progress", "Pending"),
                statuses.map { it.config.getOrNull(SemanticsProperties.StateDescription) })
            assertTrue(statuses.all { it.config.getOrNull(SemanticsActions.OnClick) == null })
            assertFalse(scene.hasText("    [x] First"))
            assertFalse(scene.hasText("Plan updated"))
            scene.frame()
        } finally { scene.close() }
    } }

    for (pending in listOf(false, true)) {
        test("empty Plan is explicit rather than a missing body pending=$pending") { offscreenTest {
            val arguments = UpdatePlanArgs(plan = emptyList())
            val scene = OffscreenScene.create {
                val renderer = rememberComposeHistoryContentRenderer()
                if (pending) HistoryPlanContent(PendingPlanUpdate("plan", arguments = arguments), renderer)
                else HistoryPlanContent(StablePlanUpdate("plan", arguments = arguments), renderer)
            }
            try {
                scene.settle { scene.hasText("(no steps provided)") }
                assertTrue(scene.hasText(if (pending) "Updating Plan" else "Update Plan"))
                scene.frame()
            } finally { scene.close() }
        } }
    }

    for (width in listOf(160, 360)) for (scale in listOf(1f, 1.5f)) {
        for (exitCode in listOf<Int?>(0, 7, null)) {
            test("recorded exec and poll outputs wrap as monospace width=$width scale=$scale exit=$exitCode") { offscreenTest {
                val output = "  EXEC_路径\t" + "long output ".repeat(18) + "\n[output truncated]\n"
                val pollOutput = "POLL_UNCHANGED_" + "long output ".repeat(18)
                val run = StableCommandExecutionToolEvent(
                    "recorded-exec", action = StableCommandExecutionAction.ExecCommand(ExecCommandArguments(
                        "fixture-command", yieldTimeMillis = 1000,
                    )), result = StableCommandExecutionResult.Output(UnifiedExecOutput(
                        "private-exec-chunk", 0.5, exitCode = exitCode, originalTokenCount = 7, output = output,
                    )),
                )
                val poll = StableCommandExecutionToolEvent(
                    "recorded-poll", action = StableCommandExecutionAction.WriteStdin(WriteStdinArguments(
                        98765, chars = "", yieldTimeMillis = 0, maxOutputTokens = 0,
                    )), result = StableCommandExecutionResult.Output(UnifiedExecOutput(
                        "private-poll-chunk", 0.1, exitCode = null, originalTokenCount = 9, output = pollOutput,
                    )),
                )
                val scene = OffscreenScene.create(width = width, height = 3000) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        val renderer = rememberComposeHistoryContentRenderer()
                        HistoryCommandContent(run, renderer)
                        HistoryCommandContent(poll, renderer)
                    }
                }
                try {
                    scene.settle { scene.hasDescription("History tool: Run: fixture-command") &&
                        scene.hasDescription("History tool: Poll terminal output") }
                    scene.clickDescription("History tool: Run: fixture-command")
                    scene.clickDescription("History tool: Poll terminal output")
                    scene.settle { scene.hasText(output) && scene.hasText(pollOutput) }
                    fun layout(value: String): TextLayoutResult {
                        val results = mutableListOf<TextLayoutResult>()
                        assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(results))
                        return results.single()
                    }
                    val runLayout = layout(output)
                    assertEquals(FontFamily.Monospace, runLayout.layoutInput.style.fontFamily)
                    assertTrue(runLayout.lineCount > 2)
                    assertFalse(runLayout.hasVisualOverflow)
                    assertEquals(output, runLayout.layoutInput.text.text)
                    assertEquals(FontFamily.Monospace, layout(pollOutput).layoutInput.style.fontFamily)
                    assertFalse(scene.hasText("private-exec-chunk"))
                    assertFalse(scene.hasText("private-poll-chunk"))
                    assertFalse(scene.hasText("98765"))
                } finally { scene.close() }
            } }
        }
    }
    for (width in listOf(160, 360)) for (scale in listOf(1f, 1.5f)) {
        for ((chars, exitCode) in listOf("" to null, "hello\n" to 0, " \n" to 7)) {
            test("recorded stdin output preserves input and wraps width=$width scale=$scale chars=${chars.length} exit=$exitCode") { offscreenTest {
                val output = "  POLL_路径\t" + "long terminal output ".repeat(12) + "\n[output truncated]\n"
                val event = StableCommandExecutionToolEvent(
                    "recorded-stdin",
                    action = StableCommandExecutionAction.WriteStdin(WriteStdinArguments(
                        98765, chars = chars, yieldTimeMillis = 0, maxOutputTokens = 0,
                    )),
                    result = StableCommandExecutionResult.Output(UnifiedExecOutput(
                        "private-stdin-chunk", 0.5, exitCode = exitCode, originalTokenCount = 7, output = output,
                    )),
                )
                val summary = if (chars.isEmpty()) "Poll terminal output" else "Write to terminal"
                val scene = OffscreenScene.create(width = width, height = 3000) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        HistoryCommandContent(event, rememberComposeHistoryContentRenderer())
                    }
                }
                try {
                    scene.settle { scene.hasDescription("History tool: $summary") }
                    scene.clickDescription("History tool: $summary")
                    scene.settle { scene.hasText(output) }
                    if (chars.isEmpty()) assertFalse(scene.hasText("chars: "))
                    else assertTrue(scene.hasText("chars: $chars"))
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(output).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
                    assertTrue(layouts.single().lineCount > 2)
                    assertFalse(layouts.single().hasVisualOverflow)
                    assertEquals(output, layouts.single().layoutInput.text.text)
                    assertFalse(scene.hasText("private-stdin-chunk"))
                    assertFalse(scene.hasText("98765"))
                } finally { scene.close() }
            } }
        }
    }
    test("command disclosure preserves multiline input and output and omits private chunk/session identifiers") { offscreenTest {
        val command = "printf first\n  " + "x".repeat(180) + "\nlast"
        val output = "  output\n[output truncated]\n"
        val event = StableCommandExecutionToolEvent(
            "recorded", action = StableCommandExecutionAction.ExecCommand(ExecCommandArguments(
                command, workdir = "/repo", yieldTimeMillis = 10_000, maxOutputTokens = 42,
            )),
            result = StableCommandExecutionResult.Output(UnifiedExecOutput("private-chunk", 0.0, exitCode = 0, originalTokenCount = 0, output = output)),
        )
        val scene = OffscreenScene.create { HistoryCommandContent(event, rememberComposeHistoryContentRenderer()) }
        try {
            scene.settle { scene.hasDescription("History tool: Run: $command") }
            assertFalse(scene.hasText(output))
            val before = scene.frame()
            scene.clickDescription("History tool: Run: $command")
            scene.settle { scene.hasText(output) }
            assertTrue(scene.hasText("Run: $command"))
            assertTrue(scene.hasText("exec_command; cwd=/repo; yield_time_ms=10000; max_output_tokens=42"))
            assertTrue(scene.hasText("output(exit_code=0; wall_time=0.0s; original_token_count=0):"))
            assertFalse(scene.hasText("private-chunk"))
            assertFalse(before.contentEquals(scene.frame()))
            scene.clickDescription("History tool: Run: $command")
            scene.settle { !scene.hasText(output) }
        } finally { scene.close() }
    } }

    val outcomes = listOf(
        StableCommandExecutionResult.Failure("No receipt") to "error: No receipt",
        StableCommandExecutionResult.Output(UnifiedExecOutput("private", 0.0, exitCode = 42, originalTokenCount = 7, output = "failed output")) to
            "output(exit_code=42; wall_time=0.0s; original_token_count=7):",
        StableCommandExecutionResult.Output(UnifiedExecOutput("private", 0.0, originalTokenCount = 0, output = "poll output")) to
            "output(wall_time=0.0s; original_token_count=0):",
    )
    for ((index, entry) in outcomes.withIndex()) {
        test("recorded result status remains distinct and never invents an exit code branch=$index") { offscreenTest {
            val event = StableCommandExecutionToolEvent(
                "outcome-$index", action = StableCommandExecutionAction.WriteStdin(WriteStdinArguments(98765, yieldTimeMillis = 0)),
                result = entry.first,
            )
            var observedStatus: String? = null
            val scene = OffscreenScene.create {
                val graphical = rememberComposeHistoryContentRenderer()
                val renderer = object : HistoryContentRenderer by graphical {
                    @Composable override fun Tool(
                        summary: String, rawName: String?, status: String, expansionKey: Any,
                        detailStyle: HistoryTextStyle, expandedSummary: String?,
                        content: @Composable (HistoryToolDetails) -> Unit,
                    ) {
                        observedStatus = status
                        graphical.Tool(summary, rawName, status, expansionKey, detailStyle, expandedSummary, content)
                    }
                }
                HistoryCommandContent(event, renderer)
            }
            try {
                scene.settle { scene.hasDescription("History tool: Poll terminal output") }
                scene.clickDescription("History tool: Poll terminal output")
                scene.settle { scene.hasText(entry.second) }
                assertEquals(if (index < 2) "failed" else "completed", observedStatus)
                assertTrue(scene.hasText("write_stdin; yield_time_ms=0"))
                assertFalse(scene.hasText("98765"))
                if (index == 2) assertFalse(scene.hasText("output(exit_code=0; wall_time=0.0s; original_token_count=0):"))
                scene.frame()
            } finally { scene.close() }
        } }
    }

    test("whitespace stdin and empty output are still rendered while polling metadata remains private") { offscreenTest {
        val event = StableCommandExecutionToolEvent(
            "whitespace", action = StableCommandExecutionAction.WriteStdin(WriteStdinArguments(87654, chars = " \n", yieldTimeMillis = 0, maxOutputTokens = 0)),
            result = StableCommandExecutionResult.Output(UnifiedExecOutput("private-chunk", 0.0, originalTokenCount = 0, output = "")),
        )
        val values = mutableListOf<String>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeHistoryContentRenderer()
            val renderer = object : HistoryContentRenderer by graphical {
                @Composable override fun Tool(
                    summary: String, rawName: String?, status: String, expansionKey: Any,
                    detailStyle: HistoryTextStyle, expandedSummary: String?,
                    content: @Composable (HistoryToolDetails) -> Unit,
                ) {
                    graphical.Tool(summary, rawName, status, expansionKey, detailStyle, expandedSummary) { details ->
                        content(object : HistoryToolDetails by details {
                            @Composable override fun Text(value: String, style: HistoryTextStyle) {
                                values += value
                                details.Text(value, style)
                            }
                            @Composable override fun RecordedOutput(value: String, style: HistoryTextStyle) {
                                values += value
                                details.RecordedOutput(value, style)
                            }
                        })
                    }
                }
            }
            HistoryCommandContent(event, renderer)
        }
        try {
            scene.settle { scene.hasDescription("History tool: Write to terminal") }
            scene.clickDescription("History tool: Write to terminal")
            scene.settle { scene.hasText("chars:  \n") }
            assertTrue("" in values)
            assertTrue(scene.hasText("write_stdin; yield_time_ms=0; max_output_tokens=0"))
            assertFalse(scene.hasText("87654"))
            scene.frame()
        } finally { scene.close() }
    } }
}
