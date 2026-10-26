package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.item.*

@Composable
public actual fun HistoryItemContent(
    item: HistoryItemViewModel, renderer: HistoryItemRenderer, shellSessions: AgentShellSessionRegistry?,
) {
    if (item is WorkGroupHistoryItemViewModel) {
        WorkGroupContent(item, renderer, shellSessions)
    } else renderer.Entry(item, item.storageIndex()) {
        StoredContent(item, renderer, shellSessions)
    }
}

@Composable
private fun WorkGroupContent(
    group: WorkGroupHistoryItemViewModel, renderer: HistoryItemRenderer, shellSessions: AgentShellSessionRegistry?,
) {
    val state by group.state.collectAsState()
    when (val current = state) {
        is WorkGroupHistoryItemState.Loading -> renderer.Loading()
        WorkGroupHistoryItemState.Failed -> renderer.Error()
        is WorkGroupHistoryItemState.Collapsed,
        is WorkGroupHistoryItemState.Expanding,
        is WorkGroupHistoryItemState.Expanded -> {
            val elapsed = when (current) {
                is WorkGroupHistoryItemState.Collapsed -> current.elapsed
                is WorkGroupHistoryItemState.Expanding -> current.elapsed
                is WorkGroupHistoryItemState.Expanded -> current.elapsed
            }
            renderer.Frame {
                renderer.GroupHeading(
                    group, "${if (current is WorkGroupHistoryItemState.Expanded) "v" else ">"} Take ${group.itemCount} " +
                        if (group.itemCount == 1) "action" else "actions",
                    elapsed, if (current is WorkGroupHistoryItemState.Collapsed) group::expand else group::collapse,
                )
                if (current is WorkGroupHistoryItemState.Expanded) {
                    current.children.forEach { HistoryItemContent(it, renderer, shellSessions) }
                }
            }
        }
    }
}

@Composable
private fun StoredContent(
    item: HistoryItemViewModel, renderer: HistoryItemRenderer, shellSessions: AgentShellSessionRegistry?,
) {
    when (item) {
        is ReasoningHistoryItemViewModel -> renderer.ItemHeading("Think", item.elapsed, HistoryTextStyle.Dim)
        is ContextCompactionHistoryItemViewModel -> renderer.ItemHeading("Context compacted", item.elapsed, HistoryTextStyle.Dim)
        is MessageHistoryItemViewModel -> HistoryMessageRow(
            item,
            heading = { text, elapsed -> renderer.ItemHeading(text, elapsed, HistoryTextStyle.Bold) },
            body = { text, supporting -> renderer.Text(text, if (supporting) HistoryTextStyle.Dim else HistoryTextStyle.Normal) },
            turnMarker = { renderer.TurnMarker(it) },
        )
        is RequestUserInputHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is RequestUserInputHistoryItemState.Loading -> renderer.Loading()
                RequestUserInputHistoryItemState.Failed -> renderer.Error()
                is RequestUserInputHistoryItemState.Ready -> renderer.Event(current.event, current.elapsed, null, shellSessions)
            }
        }
        is SuggestSubagentTaskHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is SuggestSubagentTaskHistoryItemState.Loading -> renderer.Loading()
                SuggestSubagentTaskHistoryItemState.Failed -> renderer.Error()
                is SuggestSubagentTaskHistoryItemState.Ready -> renderer.Event(current.event, current.elapsed, null, shellSessions)
            }
        }
        is PlanUpdateHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is PlanUpdateHistoryItemState.Loading -> renderer.Loading()
                PlanUpdateHistoryItemState.Failed -> renderer.Error()
                is PlanUpdateHistoryItemState.Ready -> renderer.Event(current.event, current.elapsed, null, shellSessions)
            }
        }
        is ToolHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is ToolHistoryItemState.Loading -> renderer.Loading()
                ToolHistoryItemState.Failed -> renderer.Error()
                is ToolHistoryItemState.Collapsed -> ToolCollapsed(current.header, item::expand, renderer)
                is ToolHistoryItemState.Expanding -> ToolCollapsed(current.header, item::collapse, renderer)
                is ToolHistoryItemState.Expanded -> renderer.Event(current.event, current.header.elapsed, item::collapse, shellSessions)
            }
        }
        is PatchHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is PatchHistoryItemState.Loading -> renderer.Loading()
                PatchHistoryItemState.Failed -> renderer.Error()
                is PatchHistoryItemState.Collapsed -> renderer.Collapsed(
                    current.header.summary(), current.header.status.historyStatus(), current.header.elapsed, item::expand,
                )
                is PatchHistoryItemState.Expanding -> renderer.Collapsed(
                    current.header.summary(), current.header.status.historyStatus(), current.header.elapsed, item::collapse,
                )
                is PatchHistoryItemState.Expanded -> renderer.Event(current.event, current.header.elapsed, item::collapse, shellSessions)
            }
        }
        is WorkGroupHistoryItemViewModel -> error("WorkGroup content must use its owning branch")
    }
}

@Composable
private fun ToolCollapsed(header: ToolHistoryItemHeader, onClick: () -> Unit, renderer: HistoryItemRenderer) {
    when (header) {
        is ToolHistoryItemHeader.Summary -> renderer.Collapsed(header.summary, header.status, header.elapsed, onClick)
        is ToolHistoryItemHeader.CommandExecution -> renderer.Collapsed(header.summary(), header.status(), header.elapsed, onClick)
    }
}

private fun ToolHistoryItemHeader.CommandExecution.summary(): String = when (val action = action) {
    is CommandExecutionHistoryAction.Run -> action.command.takeIf(String::isNotBlank)?.let { command ->
        if (result == CommandExecutionHistoryResult.Failure) "Failed to run: $command" else "Run: $command"
    } ?: if (result == CommandExecutionHistoryResult.Failure) "Failed to run" else "Run"
    is CommandExecutionHistoryAction.Interact -> "Write to terminal"
    is CommandExecutionHistoryAction.Wait -> "Poll terminal output"
}

private fun ToolHistoryItemHeader.CommandExecution.status(): String = when (val result = result) {
    CommandExecutionHistoryResult.Failure -> "failed"
    is CommandExecutionHistoryResult.Output -> when (result.exitCode) {
        0 -> "succeeded"
        null -> "completed"
        else -> "failed"
    }
}

private fun PatchHistoryItemHeader.summary(): String {
    val verb = when (status) {
        PatchHistoryItemStatus.Completed -> "Edit"
        PatchHistoryItemStatus.Failed -> "Failed to edit"
    }
    return when (val target = target) {
        is PatchHistoryItemTarget.SingleFile -> "$verb ${target.filename}"
        is PatchHistoryItemTarget.FileCount -> "$verb ${target.count} ${if (target.count == 1) "file" else "files"}"
    }
}

private fun PatchHistoryItemStatus.historyStatus(): String = when (this) {
    PatchHistoryItemStatus.Completed -> "completed"
    PatchHistoryItemStatus.Failed -> "failed"
}

private fun HistoryItemViewModel.storageIndex(): Int = when (this) {
    is MessageHistoryItemViewModel -> index
    is ReasoningHistoryItemViewModel -> index
    is ToolHistoryItemViewModel -> index
    is RequestUserInputHistoryItemViewModel -> index
    is SuggestSubagentTaskHistoryItemViewModel -> index
    is PatchHistoryItemViewModel -> index
    is PlanUpdateHistoryItemViewModel -> index
    is ContextCompactionHistoryItemViewModel -> index
    is WorkGroupHistoryItemViewModel -> error("A virtual history item has no context-menu storage index")
}
