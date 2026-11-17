package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRecord
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.cli.history.HistoryTextStyle as TextStyle
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration

@Composable
public actual fun HistoryEventContent(
    event: AgentCommit, renderer: HistoryContentRenderer,
    shellSessions: AgentShellSessionRegistry?, elapsed: Duration?,
) {
    CompositionLocalProvider(
        LocalHistoryRenderer provides renderer,
    ) {
        with(event) { when (this) {
            is StableUserMessage,
            is StableAssistantMessage,
            is StableDeveloperMessage,
            is StableAgentMessage,
            is StableScheduleEvent,
            is StableTerminalExitedEvent,
                ->
                HistoryMessageContent(
                    this,
                    heading = { Header(it, TextStyle.Bold) },
                    body = { text, supporting -> DetailText(text, if (supporting) TextStyle.Dim else TextStyle.Normal) },
                    assistantBody = { renderer.AssistantText(it) },
                )
            is StableAskUserEvent -> renderer.RequestUserInput(this, elapsed)
            is StableReasoning -> renderReasoning()
            is StableInvalidToolCall -> renderInvalidToolCall()
            is StableServerToolSearch -> renderServerToolSearch()
            is StableWebSearchCall -> renderHostedWebSearch()
            is StableImageGenerationCall -> renderHostedImageGeneration()
            is StableContextCompaction -> ContextCompactionEvent()
            is StablePatch -> renderer.StablePatch(this)
            is StableAwait -> renderAwaitEvent()
            is StableCommandExecution -> HistoryCommandContent(this, renderer)
            is StableJsonTool -> renderJsonTool()
            is StableTextTool -> renderTextTool()
            is StableCustomTool -> renderCustomTool()
            is StableReadMedia -> renderReadMedia()
            is StablePlanUpdate -> HistoryPlanContent(this, renderer)
            else -> throw IllegalArgumentException("Commit ${commitType.wireName} is not a conversation record.")
        } }
    }
}

internal val LocalHistoryRenderer = staticCompositionLocalOf<HistoryContentRenderer> {
    error("History content requires a renderer")
}

@Composable
public actual fun HistoryEventContent(
    event: PendingRecord, renderer: HistoryContentRenderer, shellSessions: AgentShellSessionRegistry?,
) {
    CompositionLocalProvider(LocalHistoryRenderer provides renderer) { with(event) { when (this) {
        is PendingFunctionToolCall -> ToolEvent(
            summary = functionToolOngoingSummary(name, namespace),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { JsonDetail("Arguments", arguments) }
        }

        is PendingCustomToolCall -> ToolEvent(
            summary = customToolOngoingSummary(name, namespace, input),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Input") { Detail("Input", input) }
        }

        is PendingPatch -> renderer.PendingPatch(diff)
        is PendingPlanUpdate -> HistoryPlanContent(this, renderer)
        is PendingAwait -> ToolEvent(
            summary = arguments.ongoingToolSummary(),
            rawName = "await_event",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingCommandExecution -> renderCommandExecution(shellSessions)

        is PendingReadMedia -> ToolEvent(
            summary = arguments.ongoingToolSummary(),
            rawName = "read_media",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingInvalidToolCall -> renderInvalidToolCall()
        is PendingServerToolSearch -> ToolEvent(
            summary = call.arguments.ongoingServerToolSearchSummary(),
            rawName = "server_tool_search",
            status = call.status ?: "running",
            expansionKey = call.id?.value ?: "server_tool_search",
            detailStyle = TextStyle.Dim,
        ) {
            section("Arguments") {
                JsonDetail("Arguments", call.arguments, textStyle = TextStyle.Dim)
            }
        }
    } } }
}

@Composable
private fun StableInvalidToolCall.renderInvalidToolCall() {
    if (isRedesignedInternalCall(invocation.displayName())) {
        BuiltInToolEvent(
            summary = "Model emitted an invalid tool call",
            firstLine = invocation.displayName(),
            status = "failed",
            expansionKey = callId,
        ) {
            when (val input = invocation) {
                is InvalidToolInvocation.Function -> RecordedField("arguments", input.arguments)
                is InvalidToolInvocation.Custom -> RecordedField("input", input.input)
            }
            RecordedErrorField("error", message)
        }
        return
    }
    ToolEvent(
        summary = "Model emitted an invalid tool call",
        rawName = invocation.displayName(),
        status = "failed",
        expansionKey = callId,
    ) {
        section("Invocation") { invocation.renderDetails() }
        section("Error") { DetailRecordedError("Error", message) }
    }
}

@Composable
private fun StableServerToolSearch.renderServerToolSearch() {
    BuiltInToolEvent(
        summary = functionToolSummary("server_tool_search", null, failed = output.status == "failed"),
        firstLine = call.arguments.toolSearchContext("server_tool_search"),
        status = output.status,
        expansionKey = call.id?.value ?: "server_tool_search",
    ) {
        DiscoveredTools(output.tools) {
            LocalHistoryRenderer.current.RecordedJson(it, TextStyle.Normal)
        }
    }
}

@Composable
private fun StableWebSearchCall.renderHostedWebSearch() {
    val titles = item.action.operationTitles()
    BuiltInToolEvent(
        summary = titles.firstOrNull() ?: "Search the web",
        expandedSummary = titles.joinToString("\n").ifEmpty { "Search the web" },
        firstLine = "hosted_web_search",
        status = item.status ?: "completed",
        expansionKey = item.id?.value ?: "hosted_web_search",
    )
}

@Composable
private fun StableImageGenerationCall.renderHostedImageGeneration() {
    BuiltInToolEvent(
        summary = "Generate image",
        firstLine = "hosted_image_generation",
        status = item.status,
        expansionKey = item.id?.value ?: "hosted_image_generation",
    ) {
        item.revisedPrompt?.takeIf(String::isNotEmpty)?.let { RecordedField("revised_prompt", it) }
        item.result.readableHostedImageReference()?.let { RecordedField("result", it) }
    }
}

@Composable
private fun StableReasoning.renderReasoning() {
    Header("Think", TextStyle.Dim)
}

@Composable
private fun ContextCompactionEvent() {
    Header("Context compacted", TextStyle.Dim)
}

@Composable
private fun PendingCommandExecution.renderCommandExecution(
    shellSessions: AgentShellSessionRegistry?,
) {
    val session = activeAgentShellSession(shellSessions, action.activeSessionId())
    val completed = session.completedForPresentation()
    ToolEvent(
        summary = action.ongoingToolSummary(session?.arguments),
        rawName = toolName,
        status = "running",
        expansionKey = callId,
    ) {
        section("Arguments") {
            action.renderDetails(sourceArguments = session?.arguments)
        }
        session?.let {
            section("Process") { it.renderProcessStatus(completed) }
        }
    }
}

@Composable
private fun StableJsonTool.renderJsonTool() {
    val toolName = qualifiedName(name, namespace)
    if (isReadOnlyUtility(toolName)) {
        BuiltInToolEvent(functionToolSummary(name, namespace, failed = success == false), toolName, success.completedStatus(), callId) {
            val text = (result as? JsonPrimitive)?.contentOrNull ?: result.toString()
            WrappedHistoryText(text)
        }
        return
    }
    ToolEvent(
        summary = functionToolSummary(name, namespace, failed = success == false),
        rawName = toolName,
        status = success.completedStatus(),
        expansionKey = callId,
    ) {
        section("Arguments") { JsonDetail("Arguments", arguments) }
        section("Result") { JsonDetail("Result", result) }
    }
}

@Composable
private fun StableTextTool.renderTextTool() {
    val toolName = qualifiedName(name, namespace)
    if (isReadOnlyUtility(toolName)) {
        BuiltInToolEvent(functionToolSummary(name, namespace, failed = success == false), toolName, success.completedStatus(), callId) {
            WrappedHistoryText(result)
        }
        return
    }
    ToolEvent(
        summary = functionToolSummary(name, namespace, failed = success == false),
        rawName = toolName,
        status = success.completedStatus(),
        expansionKey = callId,
    ) {
        section("Arguments") { JsonDetail("Arguments", arguments) }
        section("Result") { Detail("Result", result) }
    }
}

@Composable
private fun StableCustomTool.renderCustomTool() {
    ToolEvent(
        summary = customToolSummary(name, namespace, input, failed = success == false),
        rawName = qualifiedName(name, namespace),
        status = success.completedStatus(),
        expansionKey = callId,
    ) {
        section("Input") { Detail("Input", input) }
        section("Result") { result.renderDetails() }
    }
}

@Composable
private fun StableReadMedia.renderReadMedia() {
    BuiltInToolEvent(
        summary = "Read media: ${arguments.path}",
        firstLine = compactToolLine(
            "read_media",
            "detail" to arguments.detail?.takeUnless { it == MediaDetail.High }?.name?.lowercase(),
        ),
        status = result.status(),
        expansionKey = callId,
    ) {
        when (val outcome = result) {
            is StableReadMediaResult.Success -> {
                val requested = arguments.detail ?: MediaDetail.High
                if (outcome.output.detail != requested) {
                    RecordedField("output_detail", outcome.output.detail.name.lowercase())
                }
            }
            is StableReadMediaResult.Failure -> RecordedField("error", outcome.message)
        }
    }
}

@Composable
private fun StableAwait.renderAwaitEvent() {
    BuiltInToolEvent(
        summary = functionToolSummary("await_event", null, failed = result is StableAwaitEventResult.Failure),
        firstLine = compactToolLine(
            "await_event",
            "terminals" to arguments.terminals.takeIf { it.isNotEmpty() }?.joinToString(),
        ),
        status = when (result) {
            is StableAwaitEventResult.Output -> "succeeded"
            is StableAwaitEventResult.Failure -> "failed"
        },
        expansionKey = callId,
    ) {
        when (val outcome = result) {
            is StableAwaitEventResult.Output -> RecordedField("timestamp", outcome.value.timestamp.toString())
            is StableAwaitEventResult.Failure -> RecordedErrorField("error", outcome.message)
        }
    }
}

@Composable
private fun PendingInvalidToolCall.renderInvalidToolCall() {
    if (isRedesignedInternalCall(invocation.displayName())) {
        BuiltInToolEvent(
            summary = "Model emitted an invalid tool call",
            firstLine = invocation.displayName(),
            status = "failed",
            expansionKey = callId,
        ) {
            when (val input = invocation) {
                is PendingInvalidToolInvocation.Function -> RecordedField("arguments", input.arguments)
                is PendingInvalidToolInvocation.Custom -> RecordedField("input", input.input)
            }
            RecordedErrorField("error", message)
        }
        return
    }
    ToolEvent(
        summary = "Model emitted an invalid tool call",
        rawName = invocation.displayName(),
        status = "failed",
        expansionKey = callId,
        detailStyle = TextStyle.Dim,
    ) {
        section("Invocation") { invocation.renderDetails(TextStyle.Dim) }
        section("Error") { DetailRecordedError("Error", message, TextStyle.Dim) }
    }
}

@Composable
internal fun ToolEvent(
    summary: String,
    rawName: String?,
    status: String,
    expansionKey: Any,
    detailStyle: TextStyle = TextStyle.Normal,
    expandedSummary: String? = null,
    content: @Composable HistoryContentDetailsScope.() -> Unit,
) {
    LocalHistoryRenderer.current.Tool(
        summary, rawName, status, expansionKey, detailStyle, expandedSummary,
    ) { details ->
        HistoryContentDetailsScope(details).content()
    }
}

internal class HistoryContentDetailsScope(
    private val details: HistoryToolDetails,
) {
    @Composable
    internal fun section(
        label: String,
        content: @Composable () -> Unit,
    ) {
        details.Section(label, content)
    }
}

@Composable
private fun Header(
    value: String,
    textStyle: TextStyle,
) {
    LocalHistoryRenderer.current.Heading(value, textStyle)
}

@Composable
internal fun WrappedHistoryText(value: String, textStyle: TextStyle = TextStyle.Normal) {
    LocalHistoryRenderer.current.Text(value, textStyle)
}

@Composable
private fun Detail(
    label: String,
    value: String,
    textStyle: TextStyle = TextStyle.Normal,
) {
    if (value.isNotBlank()) DetailText("$label: $value", textStyle)
}

@Composable
private fun DetailRecordedError(label: String, value: String, fallback: TextStyle = TextStyle.Normal) {
    if (value.isNotBlank()) LocalHistoryRenderer.current.RecordedError("$label: $value", fallback)
}

@Composable
private fun DetailText(
    value: String,
    textStyle: TextStyle = TextStyle.Normal,
) {
    if (value.isNotBlank()) WrappedHistoryText(value, textStyle)
}

@Composable
private fun JsonDetail(
    label: String,
    value: JsonElement,
    textStyle: TextStyle = TextStyle.Normal,
) {
    val preview = value.historyPreview()
    if (preview.isNotBlank()) {
        LocalHistoryRenderer.current.RecordedJson("$label: $preview", textStyle)
    }
}

@Composable
private fun InvalidToolInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    when (this) {
        is InvalidToolInvocation.Function -> Detail("Arguments", arguments, textStyle)
        is InvalidToolInvocation.Custom -> Detail("Input", input, textStyle)
    }
}

@Composable
private fun PendingInvalidToolInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    when (this) {
        is PendingInvalidToolInvocation.Function -> Detail("Arguments", arguments, textStyle)
        is PendingInvalidToolInvocation.Custom -> Detail("Input", input, textStyle)
    }
}

@Composable
private fun PendingCommandExecutionAction.renderDetails(
    sourceArguments: ExecCommandArguments? = null,
    textStyle: TextStyle = TextStyle.Normal,
) {
    when (this) {
        is PendingCommandExecutionAction.ExecCommand -> arguments.renderDetails(textStyle)
        is PendingCommandExecutionAction.WriteStdin -> arguments.renderDetails(sourceArguments, textStyle)
    }
}

@Composable
private fun ExecCommandArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    Detail("Command", command, textStyle)
    workdir?.takeIf(String::isNotBlank)?.let { Detail("Working directory", it, textStyle) }
    shell?.let { Detail("Shell", it.path.toString(), textStyle) }
    Detail("Yield", "${yieldTimeMillis}ms", textStyle)
    Detail("Output limit", "$maxOutputTokens tokens", textStyle)
}

@Composable
private fun WriteStdinArguments.renderDetails(
    sourceArguments: ExecCommandArguments? = null,
    textStyle: TextStyle = TextStyle.Normal,
) {
    sourceArguments?.command?.takeIf(String::isNotBlank)?.let { Detail("Command", it, textStyle) }
    Detail("Session", sessionId.toString(), textStyle)
    Detail("Input", chars.displayStdin(), textStyle)
    Detail("Yield", "${yieldTimeMillis}ms", textStyle)
    Detail("Output limit", "$maxOutputTokens tokens", textStyle)
}

@Composable
private fun FunctionCallOutputPayload.renderDetails() {
    success?.let { Detail("Success", it.toString()) }
    when (val body = body) {
        is FunctionCallOutputBody.Text -> Detail("Result", body.text)
        is FunctionCallOutputBody.ContentItems -> body.items.forEach { item ->
            when (item) {
                is FunctionCallOutputContentItem.InputText -> Detail("Result", item.text)
                is FunctionCallOutputContentItem.InputImage -> Detail(
                    "Image",
                    "${item.imageUrl.safeMediaReference()} ${item.detail?.name?.lowercase() ?: "auto"}",
                )

                is FunctionCallOutputContentItem.EncryptedContent -> Detail("Result", "[encrypted content]")
            }
        }
    }
}

@Composable
private fun ReadMediaToolArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    Detail("Path", path, textStyle)
    Detail("Detail", detail?.name?.lowercase() ?: "high", textStyle)
}

@Composable
private fun AwaitEventArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    if (terminals.isEmpty()) {
        Detail("Terminals", "none", textStyle)
    } else {
        Detail("Terminals", terminals.joinToString(), textStyle)
    }
}

private fun PendingCommandExecutionAction.activeSessionId(): Int? = when (this) {
    is PendingCommandExecutionAction.ExecCommand -> null
    is PendingCommandExecutionAction.WriteStdin -> arguments.sessionId
}

@Composable
private fun activeAgentShellSession(
    registry: AgentShellSessionRegistry?,
    sessionId: Int?,
): AgentShellSession? {
    if (registry == null || sessionId == null) return null
    val sessions by registry.activeSessions.collectAsState()
    return sessions[sessionId]
}

@Composable
private fun AgentShellSession?.completedForPresentation(): Boolean {
    if (this == null) return false
    val completed by this.completed.collectAsState()
    return completed
}

@Composable
private fun AgentShellSession.renderProcessStatus(completed: Boolean) {
    Detail(
        label = "Process",
        value = if (completed) "finished; final output is ready" else "running",
    )
}

private fun StableReadMediaResult.status(): String = when (this) {
    is StableReadMediaResult.Success -> "succeeded"
    is StableReadMediaResult.Failure -> "failed"
}

private fun Boolean?.completedStatus(): String = when (this) {
    true -> "succeeded"
    false -> "failed"
    null -> "completed"
}

private fun InvalidToolInvocation.displayName(): String = when (this) {
    is InvalidToolInvocation.Function -> qualifiedName(name, namespace)
    is InvalidToolInvocation.Custom -> qualifiedName(name, namespace)
}

private fun PendingInvalidToolInvocation.displayName(): String = when (this) {
    is PendingInvalidToolInvocation.Function -> qualifiedName(name, namespace)
    is PendingInvalidToolInvocation.Custom -> qualifiedName(name, namespace)
}

private fun qualifiedName(
    name: String,
    namespace: String?,
): String = namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name

internal fun functionToolOngoingSummary(
    name: String,
    namespace: String?,
): String = when (qualifiedName(name, namespace)) {
    "await_event" -> "Waiting for events"
    "exec_command",
    "shell.run",
        -> "Running a command"

    "write_stdin" -> "Interacting with a terminal session"
    "read_media" -> "Reading media"
    "view_image" -> "Viewing an image"
    "image_gen.imagegen",
    "hosted_image_generation",
        -> "Generating an image"

    "request_user_input" -> "Waiting for user input"
    "tool_search",
    "server_tool_search",
        -> "Searching available tools"

    "web.run",
    "hosted_web_search",
        -> "Searching the web"

    "spawn_agent" -> "Starting an agent"
    "send_message" -> "Sending a message to an agent"
    "followup_task" -> "Resuming an agent task"
    "wait_agent" -> "Waiting for an agent"
    "interrupt_agent" -> "Interrupting an agent"
    "list_agents" -> "Listing agents"
    "update_plan" -> "Updating the plan"
    "get_context_remaining" -> "Checking remaining context"
    "clock.curr_time" -> "Checking the current time"
    else -> "Running ${qualifiedName(name, namespace)}"
}

internal fun customToolOngoingSummary(
    name: String,
    namespace: String?,
    input: String,
): String {
    if (qualifiedName(name, namespace) != "web.run") {
        return functionToolOngoingSummary(name, namespace)
    }
    val commands = runCatching {
        HistoryViewJson.decodeFromString(SearchCommands.serializer(), input)
    }.getOrNull()
    return commands?.ongoingToolSummary() ?: "Searching the web"
}

private fun PendingCommandExecutionAction.ongoingToolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String = when (this) {
    is PendingCommandExecutionAction.ExecCommand -> arguments.ongoingToolSummary()
    is PendingCommandExecutionAction.WriteStdin -> arguments.ongoingToolSummary(sourceArguments)
}

private fun ExecCommandArguments.ongoingToolSummary(): String =
    command.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { command -> "Running: $command" }
        ?: "Running a command"

private fun WriteStdinArguments.ongoingToolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String {
    val command = sourceArguments
        ?.command
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
    return when {
        command == null && chars.isEmpty() -> "Waiting for terminal session $sessionId"
        command == null -> "Interacting with terminal session $sessionId"
        chars.isEmpty() -> "Waiting for $command"
        else -> "Interacting with $command"
    }
}

private fun ReadMediaToolArguments.ongoingToolSummary(): String =
    path.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { path -> "Reading media: $path" }
        ?: "Reading media"

private fun AwaitEventArguments.ongoingToolSummary(): String =
    if (terminals.isEmpty()) {
        "Waiting for events"
    } else {
        "Waiting for events (terminals: ${terminals.joinToString()})"
    }

internal fun JsonElement.ongoingServerToolSearchSummary(): String {
    val paths = ((this as? JsonObject)?.get("paths") as? JsonArray)
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
        ?.map(String::singleLineSummary)
        ?.filter(String::isNotBlank)
        .orEmpty()
    return paths.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = "Searching cloud tools: ")
        ?: "Searching cloud tools"
}

private fun SearchCommands.ongoingToolSummary(): String = when {
    !searchQuery.isNullOrEmpty() -> searchQuery.orEmpty().first().q.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Searching the web: $query" }
        ?: "Searching the web"

    !imageQuery.isNullOrEmpty() -> imageQuery.orEmpty().first().q.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Searching images: $query" }
        ?: "Searching images"

    !open.isNullOrEmpty() -> "Opening a web page"
    !click.isNullOrEmpty() -> "Following a web link"
    !find.isNullOrEmpty() -> "Searching a web page for text"
    !screenshot.isNullOrEmpty() -> "Capturing a web page"
    !finance.isNullOrEmpty() -> "Looking up market data"
    !weather.isNullOrEmpty() -> "Checking the weather"
    !sports.isNullOrEmpty() -> "Checking sports information"
    !time.isNullOrEmpty() -> "Checking the time"
    else -> "Using web search"
}

private fun String.singleLineSummary(): String =
    lineSequence()
        .map { line -> line.trim() }
        .filter(String::isNotEmpty)
        .joinToString(" ")

private fun String.displayStdin(): String =
    if (isEmpty()) "[poll]" else replace("\r", "\\r").replace("\n", "\\n")

private val HistoryViewJson = Json {
    ignoreUnknownKeys = true
}

private fun JsonElement.historyPreview(): String = when (this) {
    is JsonObject -> when {
        containsInlineMedia() -> "[structured data with inline media]"
        stringOrNull("type") == "text" -> stringOrNull("text").orEmpty().historyTextPreview()
        stringOrNull("type") == "image" -> binaryReference()
        else -> toString().historyTextPreview()
    }

    is JsonArray ->
        if (containsInlineMedia()) {
            "[list with inline media]"
        } else {
            toString().historyTextPreview()
        }

    is JsonPrimitive -> contentOrNull?.historyTextPreview() ?: toString().historyTextPreview()
}

private fun JsonElement.containsInlineMedia(): Boolean = when (this) {
    is JsonObject ->
        stringOrNull("data") != null ||
            stringOrNull("image_url")?.startsWith("data:", ignoreCase = true) == true ||
            stringOrNull("imageUrl")?.startsWith("data:", ignoreCase = true) == true ||
            values.any(JsonElement::containsInlineMedia)

    is JsonArray -> any(JsonElement::containsInlineMedia)
    is JsonPrimitive -> contentOrNull?.startsWith("data:", ignoreCase = true) == true
}

private fun JsonObject.binaryReference(): String {
    val data = stringOrNull("data")
        ?: stringOrNull("image_url")
        ?: stringOrNull("imageUrl")
    return data?.let { "[inline image, ${it.length} characters]" } ?: "[image]"
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun String.historyTextPreview(): String = when {
    startsWith("data:", ignoreCase = true) -> safeMediaReference()
    length > MaximumInlineJsonLength ->
        take(MaximumInlineJsonLength - Ellipsis.length) + Ellipsis

    else -> this
}

private fun String.safeMediaReference(): String = when {
    startsWith("data:", ignoreCase = true) -> "[inline data, $length characters]"
    length > MaximumInlineReferenceLength ->
        take(MaximumInlineReferenceLength - Ellipsis.length) + Ellipsis

    else -> this
}

private const val MaximumInlineReferenceLength: Int = 160
private const val MaximumInlineJsonLength: Int = 512
private const val Ellipsis: String = "..."
