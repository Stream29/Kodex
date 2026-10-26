package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import kotlin.time.Duration

@Composable
public actual fun HistorySuggestionContent(
    event: StableSuggestSubagentTaskToolEvent, elapsed: Duration?, renderer: HistoryFormRenderer,
) {
    renderer.Frame {
        event.suggestSubagentTaskHistoryRows(elapsed).forEach { renderer.Row(it) }
    }
}

public actual fun StableSuggestSubagentTaskToolEvent.suggestSubagentTaskHistoryRows(
    elapsed: Duration?,
): List<RequestUserInputHistoryRowModel> = buildList {
    add(RequestUserInputHistoryRowModel(
        "Suggested Sessions" + elapsed.historySuffix(), RequestUserInputHistoryRowRole.Header,
    ))
    val completed = result as? StableSuggestSubagentTaskResult.Completed
    val accepted = completed?.response as? SuggestSubagentTaskResponse.Accepted
    arguments.tasks.forEachIndexed { index, task ->
        add(RequestUserInputHistoryRowModel(task.name, RequestUserInputHistoryRowRole.Header))
        accepted?.sessions?.getOrNull(index)?.let { session ->
            add(RequestUserInputHistoryRowModel(session.uri, RequestUserInputHistoryRowRole.Supporting))
        }
        add(RequestUserInputHistoryRowModel(task.prompt, RequestUserInputHistoryRowRole.Body))
    }
    when (val result = result) {
        is StableSuggestSubagentTaskResult.Completed -> when (val response = result.response) {
            is SuggestSubagentTaskResponse.Accepted -> add(selectedOptionRow("Accept"))
            is SuggestSubagentTaskResponse.Rejected -> {
                add(selectedOptionRow("Reject"))
                response.feedback?.takeIf(String::isNotEmpty)?.let { addAll(freeFormRows(it)) }
            }
        }
        is StableSuggestSubagentTaskResult.Failure -> add(RequestUserInputHistoryRowModel(
            "Failed to submit: ${result.message}", RequestUserInputHistoryRowRole.Error,
        ))
    }
}
