package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecDefaultMaxOutputTokens
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlin.time.Duration

@Composable
public actual fun HistoryPlanContent(event: StablePlanUpdate, renderer: HistoryContentRenderer) {
    PlanContent(event.arguments.explanation, event.arguments.plan, false, renderer)
}

@Composable
public actual fun HistoryPlanContent(event: PendingPlanUpdate, renderer: HistoryContentRenderer) {
    PlanContent(event.arguments.explanation, event.arguments.plan, true, renderer)
}

@Composable
private fun PlanContent(
    explanation: String?, plan: List<PlanItemArg>, pending: Boolean, renderer: HistoryContentRenderer,
) {
    val note = explanation?.trim()?.takeIf(String::isNotEmpty)
    renderer.Plan(HistoryPlanModel(note, plan, pending, "(no steps provided)"))
}

@Composable
public actual fun HistoryCommandContent(event: StableCommandExecutionToolEvent, renderer: HistoryContentRenderer) {
    val action = event.action
    val summary = when (action) {
        is StableCommandExecutionAction.ExecCommand -> "Run: ${action.arguments.command}"
        is StableCommandExecutionAction.WriteStdin -> if (action.arguments.chars.isEmpty()) "Poll terminal output" else "Write to terminal"
    }
    val firstLine = when (action) {
        is StableCommandExecutionAction.ExecCommand -> action.arguments.expandedCommandContext()
        is StableCommandExecutionAction.WriteStdin -> action.arguments.expandedCommandContext()
    }
    val outcome = event.result
    val status = when (outcome) {
        is StableCommandExecutionResult.Failure -> "failed"
        is StableCommandExecutionResult.Output -> when (outcome.value.exitCode) {
            null -> "completed"
            0 -> "succeeded"
            else -> "failed"
        }
    }
    renderer.Tool(summary, null, status, event.callId, HistoryTextStyle.Normal, summary) { details ->
        details.RecordedMetadata(firstLine, HistoryTextStyle.Normal)
        if (action is StableCommandExecutionAction.WriteStdin && action.arguments.chars.isNotEmpty()) {
            details.Text("chars: ${action.arguments.chars}", HistoryTextStyle.Normal)
        }
        when (outcome) {
            is StableCommandExecutionResult.Failure -> details.Text("error: ${outcome.message}", HistoryTextStyle.Normal)
            is StableCommandExecutionResult.Output -> {
                val output = outcome.value
                val metadata = buildList {
                    output.exitCode?.let { add("exit_code=$it") }
                    add("wall_time=${output.wallTimeSeconds}s")
                    add("original_token_count=${output.originalTokenCount}")
                }.joinToString("; ")
                details.RecordedMetadata("output($metadata):", HistoryTextStyle.Normal)
                details.RecordedOutput(output.output, HistoryTextStyle.Normal)
            }
        }
    }
}

public actual fun ExecCommandArguments.expandedCommandContext(): String = compactToolLine(
    "exec_command", "cwd" to workdir?.takeIf(String::isNotEmpty), "shell" to shell?.path?.toString(),
    "yield_time_ms" to yieldTimeMillis,
    "max_output_tokens" to maxOutputTokens.takeUnless { it == UnifiedExecDefaultMaxOutputTokens },
)

public actual fun WriteStdinArguments.expandedCommandContext(): String = compactToolLine(
    "write_stdin", "yield_time_ms" to yieldTimeMillis,
    "max_output_tokens" to maxOutputTokens.takeUnless { it == UnifiedExecDefaultMaxOutputTokens },
)

public actual fun compactToolLine(name: String, vararg fields: Pair<String, Any?>): String = buildString {
    append(name)
    fields.forEach { (key, value) -> if (value != null) append("; $key=$value") }
}

public actual fun Duration.historyElapsedSuffix(): String = " +${roundToMilliseconds()}"
