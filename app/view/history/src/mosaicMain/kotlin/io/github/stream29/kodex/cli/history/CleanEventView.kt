package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
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
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
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
import io.github.stream29.kodex.cli.components.EllipsizedText
import io.github.stream29.kodex.cli.components.EllipsizedTextWithTrailingContent
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.patch.PendingPatchToolEventView
import io.github.stream29.kodex.cli.patch.StablePatchToolEventView
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.LoadableToolSpec
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/** Renders one committed clean event without flattening its domain model first. */
@Composable
public fun StableCleanEvent.render(
    shellSessions: AgentShellSessionRegistry? = null,
) {
    render(shellSessions, expansion = null, elapsed = null)
}

@Composable
internal fun StableCleanEvent.render(
    shellSessions: AgentShellSessionRegistry?,
    expansion: HistoryExpansionBinding?,
    elapsed: Duration?,
) {
    CompositionLocalProvider(
        LocalHistoryExpansion provides expansion,
        LocalHistoryElapsed provides elapsed,
    ) {
        when (this) {
            is StableUserMessage -> renderUserMessage()
            is StableAssistantMessage -> renderAssistantMessage()
            is StableDeveloperMessage -> renderDeveloperMessage()
            is StableAgentMessage -> renderAgentMessage()
            is StableReasoning -> renderReasoning()
            is StableInvalidToolCall -> renderInvalidToolCall()
            is StableServerToolSearch -> renderServerToolSearch()
            is StableWebSearchCall -> renderHostedWebSearch()
            is StableImageGenerationCall -> renderHostedImageGeneration()
            is StableContextCompaction -> ContextCompactionEvent()
            is StablePatchToolEvent -> StablePatchToolEventView(
                event = this,
                expanded = expansion?.expanded?.invoke(),
                onToggleExpanded = expansion?.toggle,
                headerTrailingText = elapsed?.historyElapsedSuffix(),
            )

            is StableCommandExecutionToolEvent -> renderCommandExecution(shellSessions)
            is StableJsonToolEvent -> renderJsonTool()
            is StableTextToolEvent -> renderTextTool()
            is StableCustomToolEvent -> renderCustomTool()
            is StableImageGenerationToolEvent -> renderImageGeneration()
            is StableImageViewToolEvent -> renderImageView()
            is StableMcpToolEvent -> renderMcpTool()
            is StablePlanUpdate -> renderPlanUpdate()
            is StableRequestUserInputToolEvent -> renderRequestUserInput(elapsed)
            is StableToolSearchEvent -> renderToolSearch()
            is StableWebSearchToolEvent -> renderWebSearch()
        }
    }
}

@Stable
internal class HistoryExpansionBinding(
    val expanded: () -> Boolean,
    val toggle: () -> Unit,
)

private val LocalHistoryExpansion = staticCompositionLocalOf<HistoryExpansionBinding?> { null }
private val LocalHistoryElapsed = staticCompositionLocalOf<Duration?> { null }

/** Renders one current unfinished clean event without reducing it to raw history. */
@Composable
public fun UnstableCleanEvent.render(
    shellSessions: AgentShellSessionRegistry? = null,
) {
    when (this) {
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

        is PendingPatchToolEvent -> PendingPatchToolEventView(diff)
        is PendingPlanUpdate -> renderPlanUpdate()
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
    }
}

@Composable
private fun StableUserMessage.renderUserMessage() {
    MessageEvent("User", content, detailStyle = TextStyle.Unspecified)
}

@Composable
private fun StableAssistantMessage.renderAssistantMessage() {
    MessageEvent(
        header = "Assistant",
        content = content,
        detailStyle = TextStyle.Unspecified,
    )
}

@Composable
private fun StableDeveloperMessage.renderDeveloperMessage() {
    MessageEvent("Developer", content, detailStyle = TextStyle.Dim)
}

@Composable
private fun StableAgentMessage.renderAgentMessage() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Header("Agent $author → $recipient", TextStyle.Bold)
        content.forEach { part ->
            when (part) {
                is AgentMessageInputContent.InputText -> DetailText(part.text)
                is AgentMessageInputContent.EncryptedContent -> DetailText("[encrypted content]")
            }
        }
    }
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
    ToolEvent(
        summary = call.arguments.serverToolSearchSummary(failed = output.status == "failed"),
        rawName = "server_tool_search",
        status = output.status,
        expansionKey = call.id?.value ?: "server_tool_search",
    ) {
        section("Arguments") { JsonDetail("Arguments", call.arguments) }
        section("Tools") { LoadableTools("Tools", output.tools) }
    }
}

@Composable
private fun StableWebSearchCall.renderHostedWebSearch() {
    ToolEvent(
        summary = item.action?.toolSummary(failed = item.status == "failed")
            ?: if (item.status == "failed") "Failed to search the web" else "Search the web",
        rawName = "hosted_web_search",
        status = item.status ?: "completed",
        expansionKey = item.id?.value ?: "hosted_web_search",
    ) {
        section("Arguments") {
            item.action?.renderDetails() ?: Detail("Request", "hosted web search")
        }
    }
}

@Composable
private fun StableImageGenerationCall.renderHostedImageGeneration() {
    ToolEvent(
        summary = item.revisedPrompt?.imageGenerationSummary(failed = item.status == "failed")
            ?: if (item.status == "failed") "Failed to generate an image" else "Generate an image",
        rawName = "hosted_image_generation",
        status = item.status,
        expansionKey = item.id?.value ?: "hosted_image_generation",
    ) {
        section("Result") {
            item.revisedPrompt?.takeIf(String::isNotBlank)?.let { Detail("Revised prompt", it) }
            // Hosted image output is provider-owned binary or an opaque reference.
            // It belongs to the image surface, not the terminal transcript.
            Detail("Image", "[generated image]")
        }
    }
}

@Composable
private fun ContextCompactionEvent() {
    Header("Context compacted", TextStyle.Dim)
}

@Composable
private fun StableCommandExecutionToolEvent.renderCommandExecution(
    shellSessions: AgentShellSessionRegistry?,
) {
    val session = activeAgentShellSession(shellSessions, activeSessionId())
    renderCommandExecution(session)
}

@Composable
internal fun StableCommandExecutionToolEvent.renderCommandExecution(
    session: AgentShellSession?,
) {
    val completed = session.completedForPresentation()
    ToolEvent(
        summary = action.toolSummary(
            sourceArguments = session?.arguments,
            failed = result is StableCommandExecutionResult.Failure,
        ),
        rawName = action.toolName(),
        status = result.status(session, completed),
        expansionKey = callId,
    ) {
        section("Arguments") {
            action.renderDetails(sourceArguments = session?.arguments)
        }
        session?.let {
            section("Process") { it.renderProcessStatus(completed) }
        }
        section("Result") { result.renderDetails() }
    }
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
    ToolEvent(
        summary = arguments.toolSummary(failed = result is StableImageGenerationResult.Failure),
        rawName = "image_gen.imagegen",
        status = result.status(),
        expansionKey = callId,
    ) {
        section("Arguments") { arguments.renderDetails() }
        section("Result") {
            when (val result = result) {
                is StableImageGenerationResult.Success -> {
                    Detail("Image", "[generated inline image]")
                    result.output.outputHint
                        ?.takeIf(String::isNotBlank)
                        ?.let { Detail("Hint", it) }
                    result.savedPath
                        ?.takeIf(String::isNotBlank)
                        ?.let { Detail("Saved", it) }
                }

                is StableImageGenerationResult.Failure -> Detail("Error", result.message)
            }
        }
    }
}

@Composable
private fun StableImageViewToolEvent.renderImageView() {
    ToolEvent(
        summary = arguments.toolSummary(failed = result is StableImageViewResult.Failure),
        rawName = "view_image",
        status = result.status(),
        expansionKey = callId,
    ) {
        section("Arguments") { arguments.renderDetails() }
        section("Result") {
            when (val result = result) {
                is StableImageViewResult.Success -> {
                    Detail("Image", result.output.imageUrl.safeMediaReference())
                    Detail("Output detail", result.output.detail.name.lowercase())
                }

                is StableImageViewResult.Failure -> Detail("Error", result.message)
            }
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
    ToolEvent(
        summary = arguments.toolSummary(failed = result.status() == "failed"),
        rawName = "tool_search",
        status = result.status(),
        expansionKey = callId,
    ) {
        section("Arguments") { arguments.renderDetails() }
        section("Result") {
            when (val result = result) {
                is ToolSearchResult.Success -> LoadableTools("Tools", result.tools)
                is ToolSearchResult.InvalidArguments -> Detail("Error", result.message)
            }
        }
    }
}

@Composable
private fun StableWebSearchToolEvent.renderWebSearch() {
    ToolEvent(
        summary = commands.toolSummary(failed = result.status() == "failed"),
        rawName = "web.run",
        status = result.status(),
        expansionKey = callId,
    ) {
        section("Arguments") { commands.renderDetails() }
        section("Result") {
            when (val result = result) {
                is StableWebSearchResult.Success -> Detail("Output", result.response.output)
                is StableWebSearchResult.Failure -> Detail("Error", result.message)
            }
        }
    }
}

@Composable
private fun PendingInvalidToolCall.renderInvalidToolCall() {
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
private fun MessageEvent(
    header: String,
    content: List<ContentItem>,
    detailStyle: TextStyle,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Header(header, TextStyle.Bold)
        content.forEach { part -> part.render(detailStyle) }
    }
}

@Composable
internal fun ToolEvent(
    summary: String,
    rawName: String?,
    status: String,
    expansionKey: Any,
    detailStyle: TextStyle = TextStyle.Unspecified,
    content: @Composable ToolEventDetailsScope.() -> Unit,
) {
    var localExpanded by remember(expansionKey) { mutableStateOf(false) }
    val externalExpansion = LocalHistoryExpansion.current
    val expanded = externalExpansion?.expanded?.invoke() ?: localExpanded
    val headerColor = toolHeaderColor(status)

    Column(modifier = Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = {
                if (externalExpansion == null) {
                    localExpanded = !localExpanded
                } else {
                    externalExpansion.toggle()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            HistoryItemHeader(
                value = "${if (expanded) "v" else ">"} $summary",
                modifier = Modifier.fillMaxWidth(),
                color = headerColor,
                textStyle = tuiInteractionTextStyle(
                    hovered = isHovered,
                    pressed = isPressed,
                ),
            )
        }

        if (expanded) {
            rawName?.takeIf(String::isNotBlank)?.let { Detail("Tool", it, detailStyle) }
            ToolEventDetailsScope(detailStyle).content()
        }
    }
}

@Composable
private fun toolHeaderColor(status: String): Color = when (status) {
    "failed" -> TuiTheme.colorScheme.error
    "running", "streaming", "starting", "in_progress", "inprogress" ->
        TuiTheme.colorScheme.success

    else -> TuiTheme.colorScheme.onBackground
}

internal class ToolEventDetailsScope(
    private val detailStyle: TextStyle,
) {
    @Composable
    internal fun section(
        label: String,
        content: @Composable () -> Unit,
    ) {
        val expandedState = remember(label) { mutableStateOf(false) }
        val expanded by expandedState
        TuiPressable(
            onClick = { expandedState.value = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            WrappedHistoryText(
                value = "${if (expanded) "v" else ">"} $label",
                textStyle = tuiInteractionTextStyle(
                    hovered = isHovered,
                    pressed = isPressed,
                    idleTextStyle = detailStyle,
                ),
            )
        }
        ToolDetailBody(expandedState, content)
    }
}

@Composable
private fun ToolDetailBody(
    expandedState: State<Boolean>,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        // Keep a slot in the layout tree while collapsed so expansion can add
        // wrapped detail content during a later measure.
        val expanded = expandedState.value
        val placeable = subcompose(ToolDetailBodySlot(expanded)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (expanded) content()
            }
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        val height = if (expanded) {
            constraints.constrainHeight(placeable.height)
        } else {
            0
        }
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = height,
        ) {
            if (expanded) {
                placeable.place(0, 0)
            }
        }
    }
}

private data class ToolDetailBodySlot(val expanded: Boolean)

@Composable
private fun ExpandableHistoryEvent(
    header: String,
    expansionKey: Any,
    headerStyle: TextStyle,
    content: @Composable () -> Unit,
) {
    var localExpanded by remember(expansionKey) { mutableStateOf(false) }
    val externalExpansion = LocalHistoryExpansion.current
    val expanded = externalExpansion?.expanded?.invoke() ?: localExpanded

    Column(modifier = Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = {
                if (externalExpansion == null) {
                    localExpanded = !localExpanded
                } else {
                    externalExpansion.toggle()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            HistoryItemHeader(
                value = "${if (expanded) "v" else ">"} $header",
                modifier = Modifier.fillMaxWidth(),
                textStyle = tuiInteractionTextStyle(
                    hovered = isHovered,
                    pressed = isPressed,
                    idleTextStyle = headerStyle,
                ),
            )
        }

        if (expanded) {
            content()
        }
    }
}

@Composable
private fun Header(
    value: String,
    textStyle: TextStyle,
) {
    HistoryItemHeader(
        value = value,
        modifier = Modifier.fillMaxWidth(),
        textStyle = textStyle,
    )
}

@Composable
internal fun HistoryItemHeader(
    value: String,
    elapsed: Duration? = LocalHistoryElapsed.current,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    if (elapsed == null) {
        EllipsizedText(
            value = value,
            modifier = modifier,
            color = color,
            textStyle = textStyle,
        )
    } else {
        EllipsizedTextWithTrailingContent(
            value = value,
            modifier = modifier,
            color = color,
            textStyle = textStyle,
        ) {
            Text(
                value = elapsed.historyElapsedSuffix(),
                color = color,
                textStyle = textStyle + TextStyle.Dim,
            )
        }
    }
}

private fun Duration.historyElapsedSuffix(): String =
    " +${roundToMilliseconds()}"

internal fun Duration.roundToMilliseconds(): Duration {
    if (!isFinite()) return this
    val truncatedMilliseconds = inWholeMilliseconds
    val truncated = truncatedMilliseconds.milliseconds
    val remainder = this - truncated
    val roundedMilliseconds = when {
        remainder >= 500.microseconds -> truncatedMilliseconds + 1
        remainder <= (-500).microseconds -> truncatedMilliseconds - 1
        else -> truncatedMilliseconds
    }
    return roundedMilliseconds.milliseconds
}

@Composable
private fun Detail(
    label: String,
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    if (value.isNotBlank()) DetailText("$label: $value", textStyle)
}

@Composable
private fun DetailText(
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    if (value.isNotBlank()) WrappedHistoryText(value, textStyle)
}

@Composable
private fun JsonDetail(
    label: String,
    value: JsonElement,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail(label, value.historyPreview(), textStyle)
}

@Composable
private fun ContentItem.render(textStyle: TextStyle) {
    when (this) {
        is ContentItem.InputText -> DetailText(text, textStyle)
        is ContentItem.OutputText -> DetailText(text, textStyle)
        is ContentItem.InputImage -> Detail(
            label = "Image",
            value = "${imageUrl.safeMediaReference()} ${detail?.name?.lowercase() ?: "auto"}",
            textStyle = textStyle,
        )
    }
}

@Composable
private fun InvalidToolInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    when (this) {
        is InvalidToolInvocation.Function -> Detail("Arguments", arguments, textStyle)
        is InvalidToolInvocation.Custom -> Detail("Input", input, textStyle)
        is InvalidToolInvocation.ToolSearch -> JsonDetail("Arguments", arguments, textStyle)
    }
}

@Composable
private fun PendingInvalidToolInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    when (this) {
        is PendingInvalidToolInvocation.Function -> Detail("Arguments", arguments, textStyle)
        is PendingInvalidToolInvocation.Custom -> Detail("Input", input, textStyle)
        is PendingInvalidToolInvocation.ToolSearch -> JsonDetail("Arguments", arguments, textStyle)
    }
}

@Composable
private fun StableCommandExecutionAction.renderDetails(
    sourceArguments: ExecCommandArguments? = null,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    when (this) {
        is StableCommandExecutionAction.ExecCommand -> arguments.renderDetails(textStyle)
        is StableCommandExecutionAction.WriteStdin -> arguments.renderDetails(sourceArguments, textStyle)
    }
}

@Composable
private fun PendingCommandExecutionAction.renderDetails(
    sourceArguments: ExecCommandArguments? = null,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    when (this) {
        is PendingCommandExecutionAction.ExecCommand -> arguments.renderDetails(textStyle)
        is PendingCommandExecutionAction.WriteStdin -> arguments.renderDetails(sourceArguments, textStyle)
    }
}

@Composable
private fun ExecCommandArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
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
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    sourceArguments?.command?.takeIf(String::isNotBlank)?.let { Detail("Command", it, textStyle) }
    Detail("Session", sessionId.toString(), textStyle)
    Detail("Input", chars.displayStdin(), textStyle)
    Detail("Yield", "${yieldTimeMillis}ms", textStyle)
    Detail("Output limit", "$maxOutputTokens tokens", textStyle)
}

@Composable
private fun StableCommandExecutionResult.renderDetails() {
    when (this) {
        is StableCommandExecutionResult.Output -> value.renderDetails()
        is StableCommandExecutionResult.Failure -> Detail("Error", message)
    }
}

@Composable
private fun UnifiedExecOutput.renderDetails() {
    Detail("Chunk", chunkId)
    Detail("Elapsed", "${wallTimeSeconds}s")
    exitCode?.let { Detail("Exit code", it.toString()) }
    sessionId?.let { Detail("Session", it.toString()) }
    Detail("Original output", "$originalTokenCount tokens")
    Detail("Output", output)
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
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Prompt", prompt, textStyle)
    referencedImagePaths
        ?.takeIf { it.isNotEmpty() }
        ?.let { Detail("Referenced images", it.joinToString(), textStyle) }
    numLastImagesToInclude?.let { Detail("Recent images", it.toString(), textStyle) }
}

@Composable
private fun ViewImageToolArguments.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Path", path, textStyle)
    Detail("Detail", detail?.name?.lowercase() ?: "high", textStyle)
    environmentId?.takeIf(String::isNotBlank)?.let { Detail("Environment", it, textStyle) }
}

@Composable
private fun SearchToolCallParams.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Query", query, textStyle)
    limit?.let { Detail("Limit", it.toString(), textStyle) }
}

@Composable
private fun RequestUserInputArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
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
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    tasks.forEach { task ->
        Detail(task.name, task.prompt, textStyle)
    }
}

@Composable
private fun SearchCommands.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
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

@Composable
private fun WebSearchAction.renderDetails() {
    when (this) {
        is WebSearchAction.Search -> {
            query?.takeIf(String::isNotBlank)?.let { Detail("Search", it) }
            queries?.takeIf { it.isNotEmpty() }?.let { Detail("Search", it.joinToString()) }
            if (query.isNullOrBlank() && queries.isNullOrEmpty()) Detail("Search", "")
        }

        is WebSearchAction.OpenPage -> Detail("Open", url ?: "page")
        is WebSearchAction.FindInPage -> Detail(
            "Find",
            listOfNotNull(pattern, url).joinToString(" in ").ifBlank { "page" },
        )

        WebSearchAction.Other -> Detail("Request", "hosted web search")
    }
}

@Composable
private fun LoadableTools(
    label: String,
    tools: List<LoadableToolSpec>,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    if (tools.isEmpty()) {
        Detail(label, "none", textStyle)
    } else {
        tools.forEach { Detail(label, it.displayName(), textStyle) }
    }
}

private fun StableCommandExecutionAction.toolName(): String = when (this) {
    is StableCommandExecutionAction.ExecCommand -> "exec_command"
    is StableCommandExecutionAction.WriteStdin -> "write_stdin"
}

private fun StableCommandExecutionResult.status(
    session: AgentShellSession?,
    processCompleted: Boolean,
): String = when (this) {
    is StableCommandExecutionResult.Output -> value.exitCode?.completedStatus()
        ?: if (session == null || processCompleted) "finished" else "running"

    is StableCommandExecutionResult.Failure -> "failed"
}

private fun StableCommandExecutionToolEvent.activeSessionId(): Int? = when (val action = action) {
    is StableCommandExecutionAction.ExecCommand ->
        (result as? StableCommandExecutionResult.Output)?.value?.sessionId

    is StableCommandExecutionAction.WriteStdin -> action.arguments.sessionId
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

private fun Int?.completedStatus(): String = when (this) {
    null -> "running"
    0 -> "succeeded"
    else -> "failed"
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

private fun LoadableToolSpec.displayName(): String = when (this) {
    is ResponsesApiTool -> name
    is ResponsesApiNamespace -> name
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

private fun StableCommandExecutionAction.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
    failed: Boolean = false,
): String = when (this) {
    is StableCommandExecutionAction.ExecCommand -> arguments.toolSummary(failed)
    is StableCommandExecutionAction.WriteStdin -> arguments.toolSummary(sourceArguments, failed)
}

private fun PendingCommandExecutionAction.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String = when (this) {
    is PendingCommandExecutionAction.ExecCommand -> arguments.toolSummary()
    is PendingCommandExecutionAction.WriteStdin -> arguments.toolSummary(sourceArguments)
}

private fun ExecCommandArguments.toolSummary(failed: Boolean = false): String =
    command.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { command -> if (failed) "Failed to run: $command" else "Run: $command" }
        ?: if (failed) "Failed to run" else "Run"

private fun WriteStdinArguments.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
    failed: Boolean = false,
): String {
    val command = sourceArguments
        ?.command
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
    return when {
        command == null && chars.isEmpty() ->
            if (failed) "Failed to wait for terminal session $sessionId" else "Wait for terminal session $sessionId"

        command == null ->
            if (failed) "Failed to interact with terminal session $sessionId" else "Interact with terminal session $sessionId"

        chars.isEmpty() -> if (failed) "Failed to wait for $command" else "Wait for $command"
        else -> if (failed) "Failed to interact with $command" else "Interact with $command"
    }
}

private fun ImageGenToolArguments.toolSummary(failed: Boolean = false): String =
    prompt.imageGenerationSummary(failed)

private fun String.imageGenerationSummary(failed: Boolean = false): String =
    singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { prompt ->
            if (failed) "Failed to generate an image: $prompt" else "Generate an image: $prompt"
        }
        ?: if (failed) "Failed to generate an image" else "Generate an image"

private fun ViewImageToolArguments.toolSummary(failed: Boolean = false): String =
    path.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { path -> if (failed) "Failed to view image: $path" else "View image: $path" }
        ?: if (failed) "Failed to view an image" else "View an image"

private fun RequestUserInputArgs.toolSummary(failed: Boolean = false): String =
    questions.firstOrNull()
        ?.question
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
        ?.let { question ->
            if (failed) "Failed to collect user input: $question" else "Ask the user: $question"
        }
        ?: if (failed) "Failed to collect user input" else "Ask the user for input"

private fun SearchToolCallParams.toolSummary(failed: Boolean = false): String =
    query.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { query ->
            if (failed) "Failed to search available tools: $query" else "Search available tools: $query"
        }
        ?: if (failed) "Failed to search available tools" else "Search available tools"

internal fun JsonElement.serverToolSearchSummary(failed: Boolean = false): String {
    val paths = ((this as? JsonObject)?.get("paths") as? JsonArray)
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
        ?.map(String::singleLineSummary)
        ?.filter(String::isNotBlank)
        .orEmpty()
    val prefix = if (failed) "Failed to search cloud tools: " else "Search cloud tools: "
    return paths.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = prefix)
        ?: if (failed) "Failed to search cloud tools" else "Search cloud tools"
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

private fun WebSearchAction.toolSummary(failed: Boolean = false): String = when (this) {
    is WebSearchAction.Search -> (query ?: queries?.firstOrNull())
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
        ?.let { query -> if (failed) "Failed to search the web: $query" else "Search the web: $query" }
        ?: if (failed) "Failed to search the web" else "Search the web"

    is WebSearchAction.OpenPage -> if (failed) "Failed to open a web page" else "Open a web page"
    is WebSearchAction.FindInPage ->
        if (failed) "Failed to search a web page for text" else "Search a web page for text"

    WebSearchAction.Other -> if (failed) "Failed to use web search" else "Use web search"
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
