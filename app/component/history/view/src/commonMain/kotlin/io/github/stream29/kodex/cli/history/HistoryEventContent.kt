package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.stream29.kodex.cli.history.HistoryTextStyle as TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration

@Composable
public actual fun HistoryEventContent(
    event: StableCleanEvent, renderer: HistoryContentRenderer,
    shellSessions: AgentShellSessionRegistry?, elapsed: Duration?,
) {
    CompositionLocalProvider(
        LocalHistoryRenderer provides renderer,
    ) {
        with(event) { when (this) {
            is StableUserMessage, is StableAssistantMessage, is StableDeveloperMessage, is StableAgentMessage ->
                HistoryMessageContent(
                    this,
                    heading = { Header(it, TextStyle.Bold) },
                    body = { text, supporting -> DetailText(text, if (supporting) TextStyle.Dim else TextStyle.Normal) },
                )
            is StableReasoning -> renderReasoning()
            is StableInvalidToolCall -> renderInvalidToolCall()
            is StableServerToolSearch -> renderServerToolSearch()
            is StableWebSearchCall -> renderHostedWebSearch()
            is StableImageGenerationCall -> renderHostedImageGeneration()
            is StableContextCompaction -> ContextCompactionEvent()
            is StablePatchToolEvent -> renderer.StablePatch(this)

            is StableCommandExecutionToolEvent -> HistoryCommandContent(this, renderer)
            is StableJsonToolEvent -> renderJsonTool()
            is StableTextToolEvent -> renderTextTool()
            is StableCustomToolEvent -> renderCustomTool()
            is StableImageGenerationToolEvent -> renderImageGeneration()
            is StableImageViewToolEvent -> renderImageView()
            is StableMcpToolEvent -> renderMcpTool()
            is StablePlanUpdate -> HistoryPlanContent(this, renderer)
            is StableRequestUserInputToolEvent -> HistoryRequestUserInputContent(this, elapsed, renderer)
            is StableSuggestSubagentTaskToolEvent -> HistorySuggestionContent(this, elapsed, renderer)
            is StableToolSearchEvent -> renderToolSearch()
            is StableWebSearchToolEvent -> renderWebSearch()
        } }
    }
}

internal val LocalHistoryRenderer = staticCompositionLocalOf<HistoryContentRenderer> {
    error("History content requires a renderer")
}

@Composable
public actual fun HistoryEventContent(
    event: UnstableCleanEvent, renderer: HistoryContentRenderer, shellSessions: AgentShellSessionRegistry?,
) {
    CompositionLocalProvider(LocalHistoryRenderer provides renderer) { with(event) { when (this) {
        is PendingFunctionToolEvent -> ToolEvent(
            summary = functionToolOngoingSummary(name, namespace),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { JsonDetail("Arguments", arguments) }
        }

        is PendingCustomToolEvent -> ToolEvent(
            summary = customToolOngoingSummary(name, namespace, input),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Input") { Detail("Input", input) }
        }

        is PendingPatchToolEvent -> renderer.PendingPatch(diff)
        is PendingPlanUpdate -> HistoryPlanContent(this, renderer)
        is PendingCommandExecutionToolEvent -> renderCommandExecution(shellSessions)

        is PendingImageGenerationToolEvent -> ToolEvent(
            summary = arguments.ongoingToolSummary(),
            rawName = "image_gen.imagegen",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingImageViewToolEvent -> ToolEvent(
            summary = arguments.ongoingToolSummary(),
            rawName = "view_image",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingMcpToolEvent -> ToolEvent(
            summary = "Running ${qualifiedName(name, namespace)}",
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { JsonDetail("Arguments", arguments) }
        }

        is PendingRequestUserInputToolEvent -> ToolEvent(
            summary = arguments.ongoingToolSummary(),
            rawName = "request_user_input",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingSuggestSubagentTaskToolEvent -> ToolEvent(
            summary = "Waiting for approval to create ${arguments.tasks.size} Session(s)",
            rawName = "suggest_subagent_task",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingToolSearchEvent -> ToolEvent(
            summary = arguments.ongoingToolSummary(),
            rawName = "tool_search",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingWebSearchToolEvent -> ToolEvent(
            summary = commands.ongoingToolSummary(),
            rawName = "web.run",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { commands.renderDetails() }
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
private fun StableReasoning.renderReasoning() {
    ExpandableHistoryEvent(
        header = "Think",
        expansionKey = item.id?.value ?: "reasoning",
        headerStyle = TextStyle.Dim,
    ) {
        if (display.isNotBlank()) DetailText(display, TextStyle.Dim)
    }
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
                is InvalidToolInvocation.ToolSearch -> RecordedField("arguments", input.arguments.toString())
            }
            RecordedField("error", message)
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
        section("Error") { Detail("Error", message) }
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
        DiscoveredTools(output.tools)
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
private fun ContextCompactionEvent() {
    Header("Context compacted", TextStyle.Dim)
}

@Composable
private fun PendingCommandExecutionToolEvent.renderCommandExecution(
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
private fun StableJsonToolEvent.renderJsonTool() {
    val toolName = qualifiedName(name, namespace)
    if (isReadOnlyUtility(toolName)) {
        BuiltInToolEvent(functionToolSummary(name, namespace), toolName, success.completedStatus(), callId) {
            val text = (result as? JsonPrimitive)?.contentOrNull ?: result.toString()
            WrappedHistoryText(text)
        }
        return
    }
    ToolEvent(
        summary = functionToolSummary(name, namespace, failed = success == false),
        rawName = qualifiedName(name, namespace),
        status = success.completedStatus(),
        expansionKey = callId,
    ) {
        section("Arguments") { JsonDetail("Arguments", arguments) }
        section("Result") { JsonDetail("Result", result) }
    }
}

@Composable
private fun StableTextToolEvent.renderTextTool() {
    val toolName = qualifiedName(name, namespace)
    if (isReadOnlyUtility(toolName)) {
        BuiltInToolEvent(functionToolSummary(name, namespace), toolName, success.completedStatus(), callId) {
            WrappedHistoryText(result)
        }
        return
    }
    ToolEvent(
        summary = functionToolSummary(name, namespace, failed = success == false),
        rawName = qualifiedName(name, namespace),
        status = success.completedStatus(),
        expansionKey = callId,
    ) {
        section("Arguments") { JsonDetail("Arguments", arguments) }
        section("Result") { Detail("Result", result) }
    }
}

@Composable
private fun StableCustomToolEvent.renderCustomTool() {
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
private fun StableImageGenerationToolEvent.renderImageGeneration() {
    val outcome = result
    BuiltInToolEvent(
        summary = recordedImageTitle((outcome as? StableImageGenerationResult.Success)?.savedPath),
        firstLine = compactToolLine(
            "image_gen.imagegen",
            "referenced_image_paths" to arguments.referencedImagePaths
                ?.takeIf { it.isNotEmpty() }?.joinToString(),
            "num_last_images_to_include" to arguments.numLastImagesToInclude,
        ),
        status = result.status(),
        expansionKey = callId,
    ) {
        RecordedField("prompt", arguments.prompt)
        when (outcome) {
            is StableImageGenerationResult.Success -> {
                outcome.savedPath?.takeIf(String::isNotEmpty)?.let { RecordedField("saved_path", it) }
                outcome.output.outputHint?.takeIf(String::isNotEmpty)?.let { RecordedField("output_hint", it) }
            }
            is StableImageGenerationResult.Failure -> RecordedField("error", outcome.message)
        }
    }
}

@Composable
private fun StableImageViewToolEvent.renderImageView() {
    BuiltInToolEvent(
        summary = "View image: ${arguments.path}",
        firstLine = compactToolLine(
            "view_image",
            "detail" to arguments.detail?.takeUnless { it == io.github.stream29.kodex.tool.viewimage.ViewImageDetail.High }
                ?.name?.lowercase(),
        ),
        status = result.status(),
        expansionKey = callId,
    ) {
        when (val outcome = result) {
            is StableImageViewResult.Success -> {
                val requested = arguments.detail ?: io.github.stream29.kodex.tool.viewimage.ViewImageDetail.High
                if (outcome.output.detail != requested) {
                    RecordedField("output_detail", outcome.output.detail.name.lowercase())
                }
            }
            is StableImageViewResult.Failure -> RecordedField("error", outcome.message)
        }
    }
}

@Composable
private fun StableMcpToolEvent.renderMcpTool() {
    ToolEvent(
        summary = qualifiedName(name, namespace).stableToolSummary(result.completedStatus() == "failed"),
        rawName = qualifiedName(name, namespace),
        status = result.completedStatus(),
        expansionKey = callId,
    ) {
        section("Arguments") { JsonDetail("Arguments", arguments) }
        section("Result") { result.renderDetails() }
    }
}

@Composable
private fun StableToolSearchEvent.renderToolSearch() {
    BuiltInToolEvent(
        summary = functionToolSummary("tool_search", null, failed = result is ToolSearchResult.InvalidArguments),
        firstLine = compactToolLine("tool_search", "query" to arguments.query, "limit" to arguments.limit),
        status = result.status(),
        expansionKey = callId,
    ) {
        when (val result = result) {
            is ToolSearchResult.Success -> DiscoveredTools(result.tools)
            is ToolSearchResult.InvalidArguments -> RecordedField("error", result.message)
        }
    }
}

@Composable
private fun StableWebSearchToolEvent.renderWebSearch() {
    BuiltInToolEvent(
        summary = commands.toolSummary(failed = result.status() == "failed"),
        firstLine = compactToolLine("web.run", "response_length" to commands.responseLength?.name?.lowercase()),
        status = result.status(),
        expansionKey = callId,
    ) {
        commands.operationTitles().forEach { WrappedHistoryText(it) }
        when (val result = result) {
            is StableWebSearchResult.Success -> RecordedField("output", result.response.output)
            is StableWebSearchResult.Failure -> RecordedField("error", result.message)
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
                is PendingInvalidToolInvocation.ToolSearch -> RecordedField("arguments", input.arguments.toString())
            }
            RecordedField("error", message)
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
        section("Error") { Detail("Error", message, TextStyle.Dim) }
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
private fun ExpandableHistoryEvent(
    header: String,
    expansionKey: Any,
    headerStyle: TextStyle,
    content: @Composable () -> Unit,
) {
    LocalHistoryRenderer.current.Expandable(header, expansionKey, headerStyle, content)
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
    Detail(label, value.historyPreview(), textStyle)
}

@Composable
private fun InvalidToolInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    when (this) {
        is InvalidToolInvocation.Function -> Detail("Arguments", arguments, textStyle)
        is InvalidToolInvocation.Custom -> Detail("Input", input, textStyle)
        is InvalidToolInvocation.ToolSearch -> JsonDetail("Arguments", arguments, textStyle)
    }
}

@Composable
private fun PendingInvalidToolInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    when (this) {
        is PendingInvalidToolInvocation.Function -> Detail("Arguments", arguments, textStyle)
        is PendingInvalidToolInvocation.Custom -> Detail("Input", input, textStyle)
        is PendingInvalidToolInvocation.ToolSearch -> JsonDetail("Arguments", arguments, textStyle)
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
    if (tty) Detail("TTY", "enabled", textStyle)
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
private fun CallToolResult.renderDetails() {
    structuredContent?.let { JsonDetail("Structured result", it) }
    if (content.isEmpty() && structuredContent == null) {
        Detail("Result", "no content")
    } else {
        content.forEach { item -> item.renderMcpContent() }
    }
}

@Composable
private fun JsonElement.renderMcpContent() {
    val objectValue = this as? JsonObject
    when (objectValue?.stringOrNull("type")) {
        "text" -> Detail("Content", objectValue.stringOrNull("text").orEmpty())
        "image" -> Detail("Image", objectValue.binaryReference())
        else -> JsonDetail("Content", this)
    }
}

@Composable
private fun ImageGenToolArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    Detail("Prompt", prompt, textStyle)
    referencedImagePaths
        ?.takeIf { it.isNotEmpty() }
        ?.let { Detail("Referenced images", it.joinToString(), textStyle) }
    numLastImagesToInclude?.let { Detail("Recent images", it.toString(), textStyle) }
}

@Composable
private fun ViewImageToolArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    Detail("Path", path, textStyle)
    Detail("Detail", detail?.name?.lowercase() ?: "high", textStyle)
    environmentId?.takeIf(String::isNotBlank)?.let { Detail("Environment", it, textStyle) }
}

@Composable
private fun SearchToolCallParams.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    Detail("Query", query, textStyle)
    limit?.let { Detail("Limit", it.toString(), textStyle) }
}

@Composable
private fun RequestUserInputArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    autoResolutionMs?.let { Detail("Auto resolution", "${it}ms", textStyle) }
    questions.forEach { question ->
        Detail(question.header, question.question, textStyle)
        question.options
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString { option ->
                if (option.description.isBlank()) option.label else "${option.label} (${option.description})"
            }
            ?.let { Detail("Options", it, textStyle) }
    }
}

@Composable
private fun SuggestSubagentTaskArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    tasks.forEach { task ->
        Detail(task.name, task.prompt, textStyle)
    }
}

@Composable
private fun SearchCommands.renderDetails(
    textStyle: TextStyle = TextStyle.Normal,
) {
    var hasOperation = false
    searchQuery.orEmpty().forEach { query ->
        hasOperation = true
        Detail("Search", query.q, textStyle)
    }
    imageQuery.orEmpty().forEach { query ->
        hasOperation = true
        Detail("Image search", query.q, textStyle)
    }
    open.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Open", operation.refId + operation.lineno?.let { " at line $it" }.orEmpty(), textStyle)
    }
    click.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Click", "${operation.refId} #${operation.id}", textStyle)
    }
    find.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Find", "${operation.pattern} in ${operation.refId}", textStyle)
    }
    screenshot.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Screenshot", "${operation.refId} page ${operation.pageno}", textStyle)
    }
    finance.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Finance", "${operation.ticker} (${operation.type.name.lowercase()})", textStyle)
    }
    weather.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Weather", operation.location, textStyle)
    }
    sports.orEmpty().forEach { operation ->
        hasOperation = true
        Detail(
            "Sports",
            "${operation.league.name.lowercase()} ${operation.function.name.lowercase()}",
            textStyle,
        )
    }
    time.orEmpty().forEach { operation ->
        hasOperation = true
        Detail("Time", operation.utcOffset, textStyle)
    }
    responseLength?.let { Detail("Response length", it.name.lowercase(), textStyle) }
    if (!hasOperation) Detail("Request", "web search", textStyle)
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

private fun StableImageGenerationResult.status(): String = when (this) {
    is StableImageGenerationResult.Success -> "succeeded"
    is StableImageGenerationResult.Failure -> "failed"
}

private fun StableImageViewResult.status(): String = when (this) {
    is StableImageViewResult.Success -> "succeeded"
    is StableImageViewResult.Failure -> "failed"
}

private fun ToolSearchResult.status(): String = when (this) {
    is ToolSearchResult.Success -> "succeeded"
    is ToolSearchResult.InvalidArguments -> "failed"
}

private fun StableWebSearchResult.status(): String = when (this) {
    is StableWebSearchResult.Success -> "succeeded"
    is StableWebSearchResult.Failure -> "failed"
}

private fun Boolean?.completedStatus(): String = when (this) {
    true -> "succeeded"
    false -> "failed"
    null -> "completed"
}

private fun CallToolResult.completedStatus(): String = when (isError) {
    true -> "failed"
    false -> "succeeded"
    null -> "completed"
}

private fun InvalidToolInvocation.displayName(): String = when (this) {
    is InvalidToolInvocation.Function -> qualifiedName(name, namespace)
    is InvalidToolInvocation.Custom -> qualifiedName(name, namespace)
    is InvalidToolInvocation.ToolSearch -> "tool_search"
}

private fun PendingInvalidToolInvocation.displayName(): String = when (this) {
    is PendingInvalidToolInvocation.Function -> qualifiedName(name, namespace)
    is PendingInvalidToolInvocation.Custom -> qualifiedName(name, namespace)
    is PendingInvalidToolInvocation.ToolSearch -> "tool_search"
}

private fun qualifiedName(
    name: String,
    namespace: String?,
): String = namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name

internal fun functionToolOngoingSummary(
    name: String,
    namespace: String?,
): String = when (qualifiedName(name, namespace)) {
    "exec_command",
    "shell.run",
        -> "Running a command"

    "write_stdin" -> "Interacting with a terminal session"
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

private fun ImageGenToolArguments.ongoingToolSummary(): String =
    prompt.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { prompt -> "Generating an image: $prompt" }
        ?: "Generating an image"

private fun ViewImageToolArguments.ongoingToolSummary(): String =
    path.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { path -> "Viewing image: $path" }
        ?: "Viewing an image"

private fun RequestUserInputArgs.ongoingToolSummary(): String =
    questions.firstOrNull()
        ?.question
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
        ?.let { question -> "Waiting for user input: $question" }
        ?: "Waiting for user input"

private fun SearchToolCallParams.ongoingToolSummary(): String =
    query.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Searching available tools: $query" }
        ?: "Searching available tools"

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

internal fun functionToolSummary(
    name: String,
    namespace: String?,
    failed: Boolean = false,
): String = when (qualifiedName(name, namespace)) {
    "exec_command",
    "shell.run",
        -> if (failed) "Failed to run a command" else "Run a command"

    "write_stdin" -> if (failed) "Failed to interact with a terminal session" else "Interact with a terminal session"
    "view_image" -> if (failed) "Failed to view an image" else "View an image"
    "image_gen.imagegen" -> if (failed) "Failed to generate an image" else "Generate an image"
    "request_user_input" -> if (failed) "Failed to collect user input" else "Ask the user for input"
    "tool_search",
    "server_tool_search",
        -> if (failed) "Failed to search available tools" else "Search available tools"

    "web.run",
    "hosted_web_search",
        -> if (failed) "Failed to search the web" else "Search the web"

    "hosted_image_generation" -> if (failed) "Failed to generate an image" else "Generate an image"
    "spawn_agent" -> if (failed) "Failed to start an agent" else "Start an agent"
    "send_message" -> if (failed) "Failed to send a message to an agent" else "Message an agent"
    "followup_task" -> if (failed) "Failed to resume an agent task" else "Resume an agent task"
    "wait_agent" -> if (failed) "Failed to wait for an agent" else "Wait for an agent"
    "interrupt_agent" -> if (failed) "Failed to interrupt an agent" else "Interrupt an agent"
    "list_agents" -> if (failed) "Failed to list agents" else "List agents"
    "update_plan" -> if (failed) "Failed to update the plan" else "Update the plan"
    "get_context_remaining" -> if (failed) "Failed to check remaining context" else "Check remaining context"
    "clock.curr_time" -> if (failed) "Failed to check the current time" else "Check the current time"
    else -> qualifiedName(name, namespace).stableToolSummary(failed)
}

private fun customToolSummary(
    name: String,
    namespace: String?,
    input: String,
    failed: Boolean = false,
): String {
    if (qualifiedName(name, namespace) != "web.run") {
        return functionToolSummary(name, namespace, failed)
    }
    val commands = runCatching {
        HistoryViewJson.decodeFromString(SearchCommands.serializer(), input)
    }.getOrNull()
    return commands?.toolSummary(failed) ?: if (failed) "Failed to search the web" else "Search the web"
}

private fun SearchCommands.toolSummary(failed: Boolean = false): String = when {
    !searchQuery.isNullOrEmpty() -> searchQuery.orEmpty().first().q.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { query -> if (failed) "Failed to search the web: $query" else "Search the web: $query" }
        ?: if (failed) "Failed to search the web" else "Search the web"

    !imageQuery.isNullOrEmpty() -> imageQuery.orEmpty().first().q.singleLineSummary()
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

private fun String.stableToolSummary(failed: Boolean): String =
    if (failed) "Failed to run $this" else this

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
