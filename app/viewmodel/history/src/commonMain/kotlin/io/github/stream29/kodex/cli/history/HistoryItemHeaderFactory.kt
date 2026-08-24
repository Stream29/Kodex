package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import kotlin.time.Duration

internal fun StablePatchToolEvent.toHistoryHeader(elapsed: Duration): PatchHistoryItemHeader =
    PatchHistoryItemHeader(
        summary = "Apply patch",
        status = when (result) {
            is StablePatchToolExecutionResult.Success -> PatchHistoryItemStatus.Completed
            is StablePatchToolExecutionResult.Failure -> PatchHistoryItemStatus.Failed
        },
        elapsed = elapsed,
    )

/**
 * Produces only the one-line information needed before an ordinary tool is expanded.
 *
 * The detail payload is intentionally not traversed beyond the small result discriminators. The
 * full event remains owned by the Expanded state and is rendered by the existing event renderer.
 */
internal fun StableCleanEvent.CompletedTool.toHistoryHeader(
    elapsed: Duration,
): ToolHistoryItemHeader = ToolHistoryItemHeader(
    summary = historyToolSummary(),
    status = historyToolStatus(),
    elapsed = elapsed,
)

private fun StableCleanEvent.CompletedTool.historyToolSummary(): String = when (this) {
    is StableCleanEvent.InvalidToolCall -> "Unable to call a tool"
    is StableCleanEvent.ServerToolSearch -> "Load tools from the server"
    is StableCleanEvent.WebSearchCall -> "Search the web"
    is StableCleanEvent.ImageGenerationCall -> "Generate an image"
    is StableCommandExecutionToolEvent -> when (action) {
        is StableCommandExecutionAction.ExecCommand -> "Run command"
        is StableCommandExecutionAction.WriteStdin -> "Write to process"
    }

    is StablePatchToolEvent -> "Apply patch"
    is StableJsonToolEvent -> qualifiedToolName(name, namespace)
    is StableTextToolEvent -> qualifiedToolName(name, namespace)
    is StableCustomToolEvent -> qualifiedToolName(name, namespace)
    is StableImageGenerationToolEvent -> "Generate an image"
    is StableImageViewToolEvent -> "View image"
    is StableMcpToolEvent -> qualifiedToolName(name, namespace)
    is StableMultiAgentToolEvent -> operation.historySummary()
    is StablePlanUpdate -> "Update plan"
    is StableRequestUserInputToolEvent -> "Request user input"
    is StableToolSearchEvent -> "Search for tools"
    is StableWebSearchToolEvent -> "Search the web"
}

private fun StableCleanEvent.CompletedTool.historyToolStatus(): String = when (this) {
    is StableCleanEvent.InvalidToolCall -> "failed"
    is StableCleanEvent.ServerToolSearch -> output.status.ifBlank { "completed" }
    is StableCleanEvent.WebSearchCall -> item.status ?: "completed"
    is StableCleanEvent.ImageGenerationCall -> item.status
    is StableCommandExecutionToolEvent -> when (result) {
        is StableCommandExecutionResult.Output -> "completed"
        is StableCommandExecutionResult.Failure -> "failed"
    }

    is StablePatchToolEvent -> when (result) {
        is StablePatchToolExecutionResult.Success -> "completed"
        is StablePatchToolExecutionResult.Failure -> "failed"
    }

    is StableJsonToolEvent -> success.historyStatus()
    is StableTextToolEvent -> success.historyStatus()
    is StableCustomToolEvent -> success.historyStatus()
    is StableImageGenerationToolEvent -> result.historyStatus()
    is StableImageViewToolEvent -> result.historyStatus()
    is StableMcpToolEvent -> result.isError.historyStatus()
    is StableMultiAgentToolEvent -> operation.historyStatus()
    is StablePlanUpdate -> "completed"
    is StableRequestUserInputToolEvent -> when (result) {
        is io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult.Answered ->
            "completed"

        is io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult.Failure ->
            "failed"
    }

    is StableToolSearchEvent -> when (result) {
        is io.github.stream29.kodex.tool.toolsearch.ToolSearchResult.Success -> "completed"
        is io.github.stream29.kodex.tool.toolsearch.ToolSearchResult.InvalidArguments -> "failed"
    }

    is StableWebSearchToolEvent -> result.historyStatus()
}

private fun Boolean?.historyStatus(): String = when (this) {
    true -> "completed"
    false -> "failed"
    null -> "completed"
}

private fun StableImageGenerationResult.historyStatus(): String = when (this) {
    is StableImageGenerationResult.Success -> "completed"
    is StableImageGenerationResult.Failure -> "failed"
}

private fun StableImageViewResult.historyStatus(): String = when (this) {
    is StableImageViewResult.Success -> "completed"
    is StableImageViewResult.Failure -> "failed"
}

private fun StableWebSearchResult.historyStatus(): String = when (this) {
    is StableWebSearchResult.Success -> "completed"
    is StableWebSearchResult.Failure -> "failed"
}

private fun StableMultiAgentOperation.historySummary(): String = when (this) {
    is StableMultiAgentOperation.SpawnAgent -> "Start agent"
    is StableMultiAgentOperation.SendMessage -> "Message agent"
    is StableMultiAgentOperation.FollowupTask -> "Continue agent task"
    is StableMultiAgentOperation.WaitAgent -> "Wait for agent"
    is StableMultiAgentOperation.InterruptAgent -> "Interrupt agent"
    is StableMultiAgentOperation.ListAgents -> "List agents"
}

private fun StableMultiAgentOperation.historyStatus(): String = when (this) {
    is StableMultiAgentOperation.SpawnAgent -> result.historyStatus()
    is StableMultiAgentOperation.SendMessage -> result.historyStatus()
    is StableMultiAgentOperation.FollowupTask -> result.historyStatus()
    is StableMultiAgentOperation.WaitAgent -> result.historyStatus()
    is StableMultiAgentOperation.InterruptAgent -> result.historyStatus()
    is StableMultiAgentOperation.ListAgents -> result.historyStatus()
}

private fun StableSpawnAgentResult.historyStatus(): String = when (this) {
    is StableSpawnAgentResult.Success -> "completed"
    is StableSpawnAgentResult.Failure -> "failed"
}

private fun StableAgentDeliveryResult.historyStatus(): String = when (this) {
    is StableAgentDeliveryResult.Success -> "completed"
    is StableAgentDeliveryResult.Failure -> "failed"
}

private fun StableWaitAgentResult.historyStatus(): String = when (this) {
    is StableWaitAgentResult.Success -> "completed"
    is StableWaitAgentResult.Failure -> "failed"
}

private fun StableInterruptAgentResult.historyStatus(): String = when (this) {
    is StableInterruptAgentResult.Success -> "completed"
    is StableInterruptAgentResult.Failure -> "failed"
}

private fun StableListAgentsResult.historyStatus(): String = when (this) {
    is StableListAgentsResult.Success -> "completed"
    is StableListAgentsResult.Failure -> "failed"
}

private fun qualifiedToolName(name: String, namespace: String?): String =
    namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name
