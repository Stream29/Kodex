package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
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
    test("shared Plan shows explanation and all ordered step markers with rounded elapsed time") { offscreenTest {
        val event = StablePlanUpdate("plan", arguments = UpdatePlanArgs("  Explanation  ", listOf(
            PlanItemArg("First", StepStatus.Completed), PlanItemArg("Second", StepStatus.InProgress), PlanItemArg("Third", StepStatus.Pending),
        )))
        val scene = OffscreenScene.create { HistoryPlanContent(event, rememberComposeHistoryContentRenderer(1_500.microseconds)) }
        try {
            scene.settle { scene.hasText("• Update Plan") && scene.hasText(" +2ms") }
            val rows = listOf("  └ Explanation", "    [x] First", "    [>] Second", "    [ ] Third")
            assertTrue(rows.all(scene::hasText))
            assertEquals(rows, rows.sortedBy { scene.text(it).boundsInWindow.top })
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
                scene.settle { scene.hasText("  └ (no steps provided)") }
                assertTrue(scene.hasText(if (pending) "• Updating Plan" else "• Update Plan"))
                scene.frame()
            } finally { scene.close() }
        } }
    }

    test("command disclosure preserves multiline input and output and omits private chunk/session identifiers") { offscreenTest {
        val command = "printf first\n  " + "x".repeat(180) + "\nlast"
        val output = "  output\n[output truncated]\n"
        val event = StableCommandExecutionToolEvent(
            "recorded", action = StableCommandExecutionAction.ExecCommand(ExecCommandArguments(
                command, workdir = "/repo", tty = true, yieldTimeMillis = 10_000, maxOutputTokens = 42,
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
            assertTrue(scene.hasText("v Run: $command"))
            assertTrue(scene.hasText("exec_command; cwd=/repo; tty=true; yield_time_ms=10000; max_output_tokens=42"))
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
