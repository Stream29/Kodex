package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.unifiedexec.commandIntentDisplayName
import kotlinx.serialization.json.Json
import kotlin.time.Duration

internal fun StablePatch.toHistoryHeader(elapsed: Duration): PatchHistoryItemHeader {
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
internal fun AgentCommit.CompletedTool.toHistoryHeader(
    elapsed: Duration,
): ToolHistoryItemHeader = when (this) {
    is StableCommandExecution -> ToolHistoryItemHeader.CommandExecution(
        action = when (val action = action) {
            is StableCommandExecutionAction.ExecCommand ->
                CommandExecutionHistoryAction.Run(action.arguments.name.commandIntentDisplayName())

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

    is StableAwait,
    is StableInvalidToolCall,
    is StableServerToolSearch,
    is StableWebSearchCall,
    is StableImageGenerationCall,
    is StablePatch,
    is StableJsonTool,
    is StableTextTool,
    is StableCustomTool,
    is StablePlanUpdate,
    is StableReadMedia -> {
        val status = historyToolStatus()
        ToolHistoryItemHeader.Summary(
            summary = historyToolSummary(failed = status == "failed"),
            status = status,
            elapsed = elapsed,
        )
    }
}

private fun AgentCommit.CompletedTool.historyToolSummary(failed: Boolean): String = when (this) {
    is StableAwait -> functionToolSummary("await_event", null, failed)
    is StableInvalidToolCall -> when (val call = invocation) {
        is InvalidToolInvocation.Function -> if (call.name == "exec_command" && call.namespace == null) {
            functionToolSummary(call.name, call.namespace, failed = false)
        } else "Model emitted an invalid tool call"
        is InvalidToolInvocation.Custom -> "Model emitted an invalid tool call"
    }
    is StableServerToolSearch -> functionToolSummary("server_tool_search", null, failed)
    is StableWebSearchCall ->
        item.action?.historySummary(failed) ?: if (failed) "Failed to search the web" else "Search the web"

    is StableImageGenerationCall -> "Generate image"

    is StableCommandExecution ->
        error("Command execution uses a specialized history header.")

    is StablePatch -> "Apply patch"
    is StableJsonTool -> functionToolSummary(name, namespace, failed)
    is StableTextTool -> functionToolSummary(name, namespace, failed)
    is StableCustomTool -> customToolSummary(name, namespace, input, failed)
    is StablePlanUpdate -> if (failed) "Failed to update the plan" else "Update the plan"
    is StableReadMedia -> arguments.historySummary(failed)
}

private fun AgentCommit.CompletedTool.historyToolStatus(): String = when (this) {
    is StableAwait -> when (result) {
        is StableAwaitEventResult.Output -> "completed"
        is StableAwaitEventResult.Failure -> "failed"
    }
    is StableInvalidToolCall -> "failed"
    is StableServerToolSearch -> output.status.ifBlank { "completed" }
    is StableWebSearchCall -> item.status ?: "completed"
    is StableImageGenerationCall -> item.status
    is StableCommandExecution ->
        error("Command execution uses a specialized history header.")

    is StablePatch -> when (result) {
        is StablePatchToolExecutionResult.Success -> "completed"
        is StablePatchToolExecutionResult.Failure -> "failed"
    }

    is StableJsonTool -> success.successHistoryStatus()
    is StableTextTool -> success.successHistoryStatus()
    is StableCustomTool -> success.successHistoryStatus()
    is StablePlanUpdate -> "completed"
    is StableReadMedia -> when (result) {
        is StableReadMediaResult.Success -> "completed"
        is StableReadMediaResult.Failure -> "failed"
    }
}

public actual fun functionToolSummary(
    name: String,
    namespace: String?,
    failed: Boolean,
): String {
    val qualifiedName = qualifiedToolName(name, namespace)
    if (!failed) return when (qualifiedName) {
        "await_event" -> "Wait for events"
        "exec_command", "shell.run" -> "Run a command"
        "write_stdin" -> "Interact with a terminal session"
        "read_media" -> "Read media"
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
        "await_event" -> "Failed to wait for events"
        "exec_command", "shell.run" -> "Failed to run a command"
        "write_stdin" -> "Failed to interact with a terminal session"
        "read_media" -> "Failed to read media"
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

public actual fun customToolSummary(
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

private fun ReadMediaToolArguments.historySummary(failed: Boolean): String =
    path.historyPreview().takeIf(String::isNotBlank)
        ?.let { path -> if (failed) "Failed to read media: $path" else "Read media: $path" }
        ?: if (failed) "Failed to read media" else "Read media"

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
