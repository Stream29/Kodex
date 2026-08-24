package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentDeliveryResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInterruptAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableListAgentsResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentOperation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSpawnAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWaitAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.multiagent.FollowupTaskArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentArgs
import io.github.stream29.kodex.tool.multiagent.ListAgentsArgs
import io.github.stream29.kodex.tool.multiagent.SendMessageArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration

internal fun StablePatchToolEvent.toHistoryHeader(elapsed: Duration): PatchHistoryItemHeader {
    val paths = when (val result = result) {
        is StablePatchToolExecutionResult.Success ->
            result.applyResult.delta.changes.map { change -> change.path }

        is StablePatchToolExecutionResult.Failure -> diff.hunks.map { hunk -> hunk.path }
    }
    return PatchHistoryItemHeader(
        target = paths.toPatchHistoryTarget(),
        status = when (result) {
            is StablePatchToolExecutionResult.Success -> PatchHistoryItemStatus.Completed
            is StablePatchToolExecutionResult.Failure -> PatchHistoryItemStatus.Failed
        },
        elapsed = elapsed,
    )
}

/** Produces only the lightweight presentation retained before a tool is expanded. */
internal fun StableCleanEvent.CompletedTool.toHistoryHeader(
    elapsed: Duration,
): ToolHistoryItemHeader = when (this) {
    is StableCommandExecutionToolEvent -> ToolHistoryItemHeader.CommandExecution(
        action = when (val action = action) {
            is StableCommandExecutionAction.ExecCommand ->
                CommandExecutionHistoryAction.Run(action.arguments.command.historyPreview())

            is StableCommandExecutionAction.WriteStdin ->
                if (action.arguments.chars.isEmpty()) {
                    CommandExecutionHistoryAction.Wait(action.arguments.sessionId)
                } else {
                    CommandExecutionHistoryAction.Interact(action.arguments.sessionId)
                }
        },
        result = when (val result = result) {
            is StableCommandExecutionResult.Output -> CommandExecutionHistoryResult.Output(
                exitCode = result.value.exitCode,
                sessionId = result.value.sessionId,
            )

            is StableCommandExecutionResult.Failure -> CommandExecutionHistoryResult.Failure
        },
        elapsed = elapsed,
    )

    else -> ToolHistoryItemHeader.Summary(
        summary = historyToolSummary(),
        status = historyToolStatus(),
        elapsed = elapsed,
    )
}

private fun StableCleanEvent.CompletedTool.historyToolSummary(): String = when (this) {
    is StableCleanEvent.InvalidToolCall -> "Model emitted an invalid tool call"
    is StableCleanEvent.ServerToolSearch -> call.arguments.serverToolSearchSummary()
    is StableCleanEvent.WebSearchCall ->
        item.action?.historySummary() ?: "Search the web"

    is StableCleanEvent.ImageGenerationCall ->
        item.revisedPrompt?.imageGenerationSummary() ?: "Generate an image"

    is StableCommandExecutionToolEvent ->
        error("Command execution uses a specialized history header.")

    is StablePatchToolEvent -> "Apply patch"
    is StableJsonToolEvent -> functionToolSummary(name, namespace)
    is StableTextToolEvent -> functionToolSummary(name, namespace)
    is StableCustomToolEvent -> customToolSummary(name, namespace, input)
    is StableImageGenerationToolEvent -> arguments.historySummary()
    is StableImageViewToolEvent -> arguments.historySummary()
    is StableMcpToolEvent -> qualifiedToolName(name, namespace)
    is StableMultiAgentToolEvent -> operation.historySummary()
    is StablePlanUpdate -> "Update the plan"
    is StableRequestUserInputToolEvent -> arguments.historySummary()
    is StableToolSearchEvent -> arguments.historySummary()
    is StableWebSearchToolEvent -> commands.historySummary()
}

private fun StableCleanEvent.CompletedTool.historyToolStatus(): String = when (this) {
    is StableCleanEvent.InvalidToolCall -> "failed"
    is StableCleanEvent.ServerToolSearch -> output.status.ifBlank { "completed" }
    is StableCleanEvent.WebSearchCall -> item.status ?: "completed"
    is StableCleanEvent.ImageGenerationCall -> item.status
    is StableCommandExecutionToolEvent ->
        error("Command execution uses a specialized history header.")

    is StablePatchToolEvent -> when (result) {
        is StablePatchToolExecutionResult.Success -> "completed"
        is StablePatchToolExecutionResult.Failure -> "failed"
    }

    is StableJsonToolEvent -> success.successHistoryStatus()
    is StableTextToolEvent -> success.successHistoryStatus()
    is StableCustomToolEvent -> success.successHistoryStatus()
    is StableImageGenerationToolEvent -> result.historyStatus()
    is StableImageViewToolEvent -> result.historyStatus()
    is StableMcpToolEvent -> result.isError.mcpHistoryStatus()
    is StableMultiAgentToolEvent -> operation.historyStatus()
    is StablePlanUpdate -> "completed"
    is StableRequestUserInputToolEvent -> when (result) {
        is StableRequestUserInputResult.Answered -> "completed"
        is StableRequestUserInputResult.Failure -> "failed"
    }

    is StableToolSearchEvent -> when (result) {
        is ToolSearchResult.Success -> "completed"
        is ToolSearchResult.InvalidArguments -> "failed"
    }

    is StableWebSearchToolEvent -> result.historyStatus()
}

private fun JsonElement.serverToolSearchSummary(): String {
    val paths = ((this as? JsonObject)?.get("paths") as? JsonArray)
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
        ?.map(String::historyPreview)
        ?.filter(String::isNotBlank)
        .orEmpty()
    return paths.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = "Cloud tool search: ")
        ?: "Cloud tool search"
}

private fun functionToolSummary(name: String, namespace: String?): String =
    when (qualifiedToolName(name, namespace)) {
        "exec_command", "shell.run" -> "Run a command"
        "write_stdin" -> "Interact with a terminal session"
        "view_image" -> "View an image"
        "image_gen.imagegen" -> "Generate an image"
        "request_user_input" -> "Ask the user for input"
        "tool_search", "server_tool_search" -> "Search available tools"
        "web.run", "hosted_web_search" -> "Search the web"
        "hosted_image_generation" -> "Generate an image"
        "spawn_agent" -> "Start an agent"
        "send_message" -> "Message an agent"
        "followup_task" -> "Resume an agent task"
        "wait_agent" -> "Wait for an agent"
        "interrupt_agent" -> "Interrupt an agent"
        "list_agents" -> "List agents"
        "update_plan" -> "Update the plan"
        "get_context_remaining" -> "Check remaining context"
        "clock.curr_time" -> "Check the current time"
        else -> qualifiedToolName(name, namespace)
    }

private fun customToolSummary(name: String, namespace: String?, input: String): String {
    val qualifiedName = qualifiedToolName(name, namespace)
    if (qualifiedName != "web.run") return functionToolSummary(name, namespace)
    val commands = runCatching {
        HistoryHeaderJson.decodeFromString(SearchCommands.serializer(), input)
    }.getOrNull()
    return commands?.historySummary() ?: "Search the web"
}

private fun ImageGenToolArguments.historySummary(): String = prompt.imageGenerationSummary()

private fun String.imageGenerationSummary(): String =
    historyPreview().takeIf(String::isNotBlank)
        ?.let { prompt -> "Generate an image: $prompt" }
        ?: "Generate an image"

private fun ViewImageToolArguments.historySummary(): String =
    path.historyPreview().takeIf(String::isNotBlank)
        ?.let { path -> "View image: $path" }
        ?: "View an image"

private fun RequestUserInputArgs.historySummary(): String =
    questions.firstOrNull()?.question?.historyPreview()
        ?.takeIf(String::isNotBlank)
        ?.let { question -> "Ask the user: $question" }
        ?: "Ask the user for input"

private fun SearchToolCallParams.historySummary(): String =
    query.historyPreview().takeIf(String::isNotBlank)
        ?.let { query -> "Search available tools: $query" }
        ?: "Search available tools"

private fun SearchCommands.historySummary(): String = when {
    !searchQuery.isNullOrEmpty() -> searchQuery.orEmpty().first().q.historyPreview()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Search the web: $query" }
        ?: "Search the web"

    !imageQuery.isNullOrEmpty() -> imageQuery.orEmpty().first().q.historyPreview()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Search images: $query" }
        ?: "Search images"

    !open.isNullOrEmpty() -> "Open a web page"
    !click.isNullOrEmpty() -> "Follow a web link"
    !find.isNullOrEmpty() -> "Find text on a web page"
    !screenshot.isNullOrEmpty() -> "Capture a web page"
    !finance.isNullOrEmpty() -> "Look up market data"
    !weather.isNullOrEmpty() -> "Check the weather"
    !sports.isNullOrEmpty() -> "Check sports information"
    !time.isNullOrEmpty() -> "Check the time"
    else -> "Use web search"
}

private fun WebSearchAction.historySummary(): String = when (this) {
    is WebSearchAction.Search -> (query ?: queries?.firstOrNull())
        ?.historyPreview()
        ?.takeIf(String::isNotBlank)
        ?.let { query -> "Search the web: $query" }
        ?: "Search the web"

    is WebSearchAction.OpenPage -> "Open a web page"
    is WebSearchAction.FindInPage -> "Find text on a web page"
    WebSearchAction.Other -> "Use web search"
}

private fun StableMultiAgentOperation.historySummary(): String = when (this) {
    is StableMultiAgentOperation.SpawnAgent -> arguments.historySummary()
    is StableMultiAgentOperation.SendMessage -> arguments.historySummary()
    is StableMultiAgentOperation.FollowupTask -> arguments.historySummary()
    is StableMultiAgentOperation.WaitAgent -> "Wait for an agent"
    is StableMultiAgentOperation.InterruptAgent -> arguments.historySummary()
    is StableMultiAgentOperation.ListAgents -> arguments.historySummary()
}

private fun SpawnAgentArgs.historySummary(): String =
    "Start agent: ${taskName.historyPreview()}"

private fun SendMessageArgs.historySummary(): String =
    "Message agent: ${target.historyPreview()}"

private fun FollowupTaskArgs.historySummary(): String =
    "Resume task for agent: ${target.historyPreview()}"

private fun InterruptAgentArgs.historySummary(): String =
    "Interrupt agent: ${target.historyPreview()}"

private fun ListAgentsArgs.historySummary(): String =
    pathPrefix?.historyPreview()?.takeIf(String::isNotBlank)
        ?.let { prefix -> "List agents under: $prefix" }
        ?: "List agents"

private fun Boolean?.successHistoryStatus(): String = when (this) {
    true -> "completed"
    false -> "failed"
    null -> "completed"
}

private fun Boolean?.mcpHistoryStatus(): String = when (this) {
    true -> "failed"
    false, null -> "completed"
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

private fun List<String>.toPatchHistoryTarget(): PatchHistoryItemTarget {
    val distinctPaths = distinct()
    if (distinctPaths.size != 1) return PatchHistoryItemTarget.FileCount(distinctPaths.size)
    val path = distinctPaths.single()
    val filename = path.substringAfterLast('/').substringAfterLast('\\')
        .takeIf(String::isNotBlank)
        ?: path.takeIf(String::isNotBlank)
        ?: "file"
    return PatchHistoryItemTarget.SingleFile(filename)
}

private fun qualifiedToolName(name: String, namespace: String?): String =
    namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name

private fun String.historyPreview(): String {
    val singleLine = lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
    return if (singleLine.length <= MaximumHistoryPreviewLength) {
        singleLine
    } else {
        singleLine.take(MaximumHistoryPreviewLength - 3).trimEnd() + "..."
    }
}

private val HistoryHeaderJson = Json {
    ignoreUnknownKeys = true
}

private const val MaximumHistoryPreviewLength: Int = 240
