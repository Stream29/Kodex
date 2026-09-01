package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
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
    val paths = diff.hunks.map { hunk -> hunk.path }
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

    else -> {
        val status = historyToolStatus()
        ToolHistoryItemHeader.Summary(
            summary = historyToolSummary(failed = status == "failed"),
            status = status,
            elapsed = elapsed,
        )
    }
}

private fun StableCleanEvent.CompletedTool.historyToolSummary(failed: Boolean): String = when (this) {
    is StableInvalidToolCall -> "Model emitted an invalid tool call"
    is StableServerToolSearch -> call.arguments.serverToolSearchSummary(failed)
    is StableWebSearchCall ->
        item.action?.historySummary(failed) ?: if (failed) "Failed to search the web" else "Search the web"

    is StableImageGenerationCall ->
        item.revisedPrompt?.imageGenerationSummary(failed)
            ?: if (failed) "Failed to generate an image" else "Generate an image"

    is StableCommandExecutionToolEvent ->
        error("Command execution uses a specialized history header.")

    is StablePatchToolEvent -> "Apply patch"
    is StableJsonToolEvent -> functionToolSummary(name, namespace, failed)
    is StableTextToolEvent -> functionToolSummary(name, namespace, failed)
    is StableCustomToolEvent -> customToolSummary(name, namespace, input, failed)
    is StableImageGenerationToolEvent -> arguments.historySummary(failed)
    is StableImageViewToolEvent -> arguments.historySummary(failed)
    is StableMcpToolEvent -> qualifiedToolName(name, namespace).stableToolSummary(failed)
    is StablePlanUpdate -> if (failed) "Failed to update the plan" else "Update the plan"
    is StableRequestUserInputToolEvent -> arguments.historySummary(failed)
    is StableToolSearchEvent -> arguments.historySummary(failed)
    is StableWebSearchToolEvent -> commands.historySummary(failed)
    else -> error("Unknown completed tool event: ${this::class.simpleName}")
}

private fun StableCleanEvent.CompletedTool.historyToolStatus(): String = when (this) {
    is StableInvalidToolCall -> "failed"
    is StableServerToolSearch -> output.status.ifBlank { "completed" }
    is StableWebSearchCall -> item.status ?: "completed"
    is StableImageGenerationCall -> item.status
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
    else -> error("Unknown completed tool event: ${this::class.simpleName}")
}

private fun JsonElement.serverToolSearchSummary(failed: Boolean): String {
    val paths = ((this as? JsonObject)?.get("paths") as? JsonArray)
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
        ?.map(String::historyPreview)
        ?.filter(String::isNotBlank)
        .orEmpty()
    val detail = paths.takeIf(List<String>::isNotEmpty)?.joinToString()
    val prefix = if (failed) "Failed to search cloud tools" else "Search cloud tools"
    return detail?.let { "$prefix: $it" } ?: prefix
}

private fun functionToolSummary(name: String, namespace: String?, failed: Boolean): String {
    val qualifiedName = qualifiedToolName(name, namespace)
    if (!failed) return when (qualifiedName) {
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
        else -> qualifiedName
    }
    return when (qualifiedName) {
        "exec_command", "shell.run" -> "Failed to run a command"
        "write_stdin" -> "Failed to interact with a terminal session"
        "view_image" -> "Failed to view an image"
        "image_gen.imagegen", "hosted_image_generation" -> "Failed to generate an image"
        "request_user_input" -> "Failed to collect user input"
        "tool_search", "server_tool_search" -> "Failed to search available tools"
        "web.run", "hosted_web_search" -> "Failed to search the web"
        "spawn_agent" -> "Failed to start an agent"
        "send_message" -> "Failed to send a message to an agent"
        "followup_task" -> "Failed to resume an agent task"
        "wait_agent" -> "Failed to wait for an agent"
        "interrupt_agent" -> "Failed to interrupt an agent"
        "list_agents" -> "Failed to list agents"
        "update_plan" -> "Failed to update the plan"
        "get_context_remaining" -> "Failed to check remaining context"
        "clock.curr_time" -> "Failed to check the current time"
        else -> qualifiedName.stableToolSummary(failed = true)
    }
}

private fun customToolSummary(
    name: String,
    namespace: String?,
    input: String,
    failed: Boolean,
): String {
    val qualifiedName = qualifiedToolName(name, namespace)
    if (qualifiedName != "web.run") return functionToolSummary(name, namespace, failed)
    val commands = runCatching {
        HistoryHeaderJson.decodeFromString(SearchCommands.serializer(), input)
    }.getOrNull()
    return commands?.historySummary(failed) ?: if (failed) "Failed to search the web" else "Search the web"
}

private fun ImageGenToolArguments.historySummary(failed: Boolean): String = prompt.imageGenerationSummary(failed)

private fun String.imageGenerationSummary(failed: Boolean): String =
    historyPreview().takeIf(String::isNotBlank)
        ?.let { prompt -> if (failed) "Failed to generate an image: $prompt" else "Generate an image: $prompt" }
        ?: if (failed) "Failed to generate an image" else "Generate an image"

private fun ViewImageToolArguments.historySummary(failed: Boolean): String =
    path.historyPreview().takeIf(String::isNotBlank)
        ?.let { path -> if (failed) "Failed to view image: $path" else "View image: $path" }
        ?: if (failed) "Failed to view an image" else "View an image"

private fun RequestUserInputArgs.historySummary(failed: Boolean): String =
    questions.firstOrNull()?.question?.historyPreview()
        ?.takeIf(String::isNotBlank)
        ?.let { question -> if (failed) "Failed to collect user input: $question" else "Ask the user: $question" }
        ?: if (failed) "Failed to collect user input" else "Ask the user for input"

private fun SearchToolCallParams.historySummary(failed: Boolean): String =
    query.historyPreview().takeIf(String::isNotBlank)
        ?.let { query -> if (failed) "Failed to search available tools: $query" else "Search available tools: $query" }
        ?: if (failed) "Failed to search available tools" else "Search available tools"

private fun SearchCommands.historySummary(failed: Boolean): String = when {
    !searchQuery.isNullOrEmpty() -> searchQuery.orEmpty().first().q.historyPreview()
        .takeIf(String::isNotBlank)
        ?.let { query -> if (failed) "Failed to search the web: $query" else "Search the web: $query" }
        ?: if (failed) "Failed to search the web" else "Search the web"

    !imageQuery.isNullOrEmpty() -> imageQuery.orEmpty().first().q.historyPreview()
        .takeIf(String::isNotBlank)
        ?.let { query -> if (failed) "Failed to search images: $query" else "Search images: $query" }
        ?: if (failed) "Failed to search images" else "Search images"

    !open.isNullOrEmpty() -> if (failed) "Failed to open a web page" else "Open a web page"
    !click.isNullOrEmpty() -> if (failed) "Failed to follow a web link" else "Follow a web link"
    !find.isNullOrEmpty() -> if (failed) "Failed to search a web page for text" else "Search a web page for text"
    !screenshot.isNullOrEmpty() -> if (failed) "Failed to capture a web page" else "Capture a web page"
    !finance.isNullOrEmpty() -> if (failed) "Failed to look up market data" else "Look up market data"
    !weather.isNullOrEmpty() -> if (failed) "Failed to check the weather" else "Check the weather"
    !sports.isNullOrEmpty() -> if (failed) "Failed to check sports information" else "Check sports information"
    !time.isNullOrEmpty() -> if (failed) "Failed to check the time" else "Check the time"
    else -> if (failed) "Failed to use web search" else "Use web search"
}

private fun WebSearchAction.historySummary(failed: Boolean): String = when (this) {
    is WebSearchAction.Search -> (query ?: queries?.firstOrNull())
        ?.historyPreview()
        ?.takeIf(String::isNotBlank)
        ?.let { query -> if (failed) "Failed to search the web: $query" else "Search the web: $query" }
        ?: if (failed) "Failed to search the web" else "Search the web"

    is WebSearchAction.OpenPage -> if (failed) "Failed to open a web page" else "Open a web page"
    is WebSearchAction.FindInPage -> if (failed) {
        "Failed to search a web page for text"
    } else {
        "Search a web page for text"
    }

    WebSearchAction.Other -> if (failed) "Failed to use web search" else "Use web search"
}

private fun String.stableToolSummary(failed: Boolean): String =
    if (failed) "Failed to run $this" else this

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
