package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
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
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSpawnAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWaitAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.patch.PendingPatchToolEventView
import io.github.stream29.kodex.cli.patch.StablePatchToolEventView
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.LoadableToolSpec
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.multiagent.FollowupTaskArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentResult
import io.github.stream29.kodex.tool.multiagent.ListAgentsArgs
import io.github.stream29.kodex.tool.multiagent.ListAgentsResult
import io.github.stream29.kodex.tool.multiagent.MultiAgentStatus
import io.github.stream29.kodex.tool.multiagent.SendMessageArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentResult
import io.github.stream29.kodex.tool.multiagent.SpawnForkMode
import io.github.stream29.kodex.tool.multiagent.WaitAgentArgs
import io.github.stream29.kodex.tool.multiagent.WaitAgentResult
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Renders one committed clean event without flattening its domain model first. */
@Composable
public fun StableCleanEvent.render(
    unifiedExecToolClient: UnifiedExecToolClient? = null,
) {
    when (this) {
        is StableCleanEvent.UserMessage -> renderUserMessage()
        is StableCleanEvent.AssistantMessage -> renderAssistantMessage()
        is StableCleanEvent.DeveloperMessage -> renderDeveloperMessage()
        is StableCleanEvent.AgentMessage -> renderAgentMessage()
        is StableCleanEvent.Reasoning -> renderReasoning()
        is StableCleanEvent.InvalidToolCall -> renderInvalidToolCall()
        is StableCleanEvent.ServerToolSearch -> renderServerToolSearch()
        is StableCleanEvent.WebSearchCall -> renderHostedWebSearch()
        is StableCleanEvent.ImageGenerationCall -> renderHostedImageGeneration()
        StableCleanEvent.ContextCompaction -> ContextCompactionEvent()
        is StablePatchToolEvent -> StablePatchToolEventView(this)
        is StableCommandExecutionToolEvent -> renderCommandExecution(unifiedExecToolClient)
        is StableJsonToolEvent -> renderJsonTool()
        is StableTextToolEvent -> renderTextTool()
        is StableCustomToolEvent -> renderCustomTool()
        is StableImageGenerationToolEvent -> renderImageGeneration()
        is StableImageViewToolEvent -> renderImageView()
        is StableMcpToolEvent -> renderMcpTool()
        is StableMultiAgentToolEvent -> renderMultiAgentTool()
        is StablePlanUpdate -> renderPlanUpdate()
        is StableRequestUserInputToolEvent -> renderRequestUserInput()
        is StableToolSearchEvent -> renderToolSearch()
        is StableWebSearchToolEvent -> renderWebSearch()
    }
}

/** Renders one current unfinished clean event without reducing it to raw history. */
@Composable
public fun UnstableCleanEvent.render(
    unifiedExecToolClient: UnifiedExecToolClient? = null,
) {
    when (this) {
        is PendingFunctionToolEvent -> ToolEvent(
            summary = functionToolSummary(name, namespace),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { JsonDetail("Arguments", arguments) }
        }

        is PendingCustomToolEvent -> ToolEvent(
            summary = functionToolSummary(name, namespace),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Input") { Detail("Input", input) }
        }

        is PendingPatchToolEvent -> PendingPatchToolEventView(diff)
        is PendingPlanUpdate -> renderPlanUpdate()
        is PendingCommandExecutionToolEvent -> renderCommandExecution(unifiedExecToolClient)

        is PendingMultiAgentToolEvent -> renderPendingMultiAgentTool()
        is PendingImageGenerationToolEvent -> ToolEvent(
            summary = arguments.toolSummary(),
            rawName = "image_gen.imagegen",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingImageViewToolEvent -> ToolEvent(
            summary = arguments.toolSummary(),
            rawName = "view_image",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingMcpToolEvent -> ToolEvent(
            summary = qualifiedName(name, namespace),
            rawName = qualifiedName(name, namespace),
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { JsonDetail("Arguments", arguments) }
        }

        is PendingRequestUserInputToolEvent -> ToolEvent(
            summary = arguments.toolSummary(),
            rawName = "request_user_input",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingToolSearchEvent -> ToolEvent(
            summary = arguments.toolSummary(),
            rawName = "tool_search",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { arguments.renderDetails() }
        }

        is PendingWebSearchToolEvent -> ToolEvent(
            summary = commands.toolSummary(),
            rawName = "web.run",
            status = "running",
            expansionKey = callId,
        ) {
            section("Arguments") { commands.renderDetails() }
        }

        is PendingInvalidToolCall -> renderInvalidToolCall()
        is PendingServerToolSearch -> ToolEvent(
            summary = "Load tools from the server",
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
private fun StableCleanEvent.UserMessage.renderUserMessage() {
    MessageEvent("You", content, detailStyle = TextStyle.Unspecified)
}

@Composable
private fun StableCleanEvent.AssistantMessage.renderAssistantMessage() {
    MessageEvent(
        header = phase?.let { "Assistant · ${it.displayName()}" } ?: "Assistant",
        content = content,
        detailStyle = TextStyle.Unspecified,
    )
}

@Composable
private fun StableCleanEvent.DeveloperMessage.renderDeveloperMessage() {
    MessageEvent("Developer", content, detailStyle = TextStyle.Dim)
}

@Composable
private fun StableCleanEvent.AgentMessage.renderAgentMessage() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Header("$author → $recipient", TextStyle.Bold)
        content.forEach { part ->
            when (part) {
                is AgentMessageInputContent.InputText -> DetailText(part.text)
                is AgentMessageInputContent.EncryptedContent -> DetailText("[encrypted content]")
            }
        }
    }
}

@Composable
private fun StableCleanEvent.Reasoning.renderReasoning() {
    ExpandableHistoryEvent(
        header = "Thinking",
        expansionKey = item.id?.value ?: "reasoning",
        headerStyle = TextStyle.Dim,
    ) {
        if (display.isNotBlank()) DetailText(display, TextStyle.Dim)
    }
}

@Composable
private fun StableCleanEvent.InvalidToolCall.renderInvalidToolCall() {
    ToolEvent(
        summary = "Unable to call a tool",
        rawName = invocation.displayName(),
        status = "failed",
        expansionKey = callId,
    ) {
        section("Invocation") { invocation.renderDetails() }
        section("Error") { Detail("Error", message) }
    }
}

@Composable
private fun StableCleanEvent.ServerToolSearch.renderServerToolSearch() {
    ToolEvent(
        summary = "Load tools from the server",
        rawName = "server_tool_search",
        status = output.status,
        expansionKey = call.id?.value ?: "server_tool_search",
    ) {
        section("Arguments") { JsonDetail("Arguments", call.arguments) }
        section("Tools") { LoadableTools("Tools", output.tools) }
    }
}

@Composable
private fun StableCleanEvent.WebSearchCall.renderHostedWebSearch() {
    ToolEvent(
        summary = item.action?.toolSummary() ?: "Search the web",
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
private fun StableCleanEvent.ImageGenerationCall.renderHostedImageGeneration() {
    ToolEvent(
        summary = item.revisedPrompt?.imageGenerationSummary() ?: "Generate an image",
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
    unifiedExecToolClient: UnifiedExecToolClient?,
) {
    val session = activeUnifiedExecProcessSession(unifiedExecToolClient, activeSessionId())
    renderCommandExecution(session)
}

@Composable
internal fun StableCommandExecutionToolEvent.renderCommandExecution(
    session: UnifiedExecProcessSession?,
) {
    val completed = session.completedForPresentation()
    ToolEvent(
        summary = action.toolSummary(session?.arguments),
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
    unifiedExecToolClient: UnifiedExecToolClient?,
) {
    val session = activeUnifiedExecProcessSession(unifiedExecToolClient, action.activeSessionId())
    val completed = session.completedForPresentation()
    ToolEvent(
        summary = action.toolSummary(session?.arguments),
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
        summary = functionToolSummary(name, namespace),
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
        summary = functionToolSummary(name, namespace),
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
        summary = functionToolSummary(name, namespace),
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
        summary = arguments.toolSummary(),
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
        summary = arguments.toolSummary(),
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
        summary = qualifiedName(name, namespace),
        rawName = qualifiedName(name, namespace),
        status = result.completedStatus(),
        expansionKey = callId,
    ) {
        section("Arguments") { JsonDetail("Arguments", arguments) }
        section("Result") { result.renderDetails() }
    }
}

@Composable
private fun StableMultiAgentToolEvent.renderMultiAgentTool() {
    when (val operation = operation) {
        is StableMultiAgentOperation.SpawnAgent -> ToolEvent(
            summary = operation.arguments.toolSummary(),
            rawName = "spawn_agent",
            status = operation.result.completedStatus(),
            expansionKey = this@renderMultiAgentTool.callId,
        ) {
            section("Arguments") { operation.arguments.renderDetails() }
            section("Result") { operation.result.renderDetails() }
        }

        is StableMultiAgentOperation.SendMessage -> ToolEvent(
            summary = operation.arguments.toolSummary(),
            rawName = "send_message",
            status = operation.result.completedStatus(),
            expansionKey = this@renderMultiAgentTool.callId,
        ) {
            section("Arguments") { operation.arguments.renderDetails() }
            section("Result") { operation.result.renderDetails() }
        }

        is StableMultiAgentOperation.FollowupTask -> ToolEvent(
            summary = operation.arguments.toolSummary(),
            rawName = "followup_task",
            status = operation.result.completedStatus(),
            expansionKey = this@renderMultiAgentTool.callId,
        ) {
            section("Arguments") { operation.arguments.renderDetails() }
            section("Result") { operation.result.renderDetails() }
        }

        is StableMultiAgentOperation.WaitAgent -> ToolEvent(
            summary = operation.arguments.toolSummary(),
            rawName = "wait_agent",
            status = operation.result.completedStatus(),
            expansionKey = this@renderMultiAgentTool.callId,
        ) {
            section("Arguments") { operation.arguments.renderDetails() }
            section("Result") { operation.result.renderDetails() }
        }

        is StableMultiAgentOperation.InterruptAgent -> ToolEvent(
            summary = operation.arguments.toolSummary(),
            rawName = "interrupt_agent",
            status = operation.result.completedStatus(),
            expansionKey = this@renderMultiAgentTool.callId,
        ) {
            section("Arguments") { operation.arguments.renderDetails() }
            section("Result") { operation.result.renderDetails() }
        }

        is StableMultiAgentOperation.ListAgents -> ToolEvent(
            summary = operation.arguments.toolSummary(),
            rawName = "list_agents",
            status = operation.result.completedStatus(),
            expansionKey = this@renderMultiAgentTool.callId,
        ) {
            section("Arguments") { operation.arguments.renderDetails() }
            section("Result") { operation.result.renderDetails() }
        }
    }
}

@Composable
private fun StableRequestUserInputToolEvent.renderRequestUserInput() {
    ToolEvent(
        summary = arguments.toolSummary(),
        rawName = "request_user_input",
        status = result.status(),
        expansionKey = callId,
    ) {
        section("Arguments") { arguments.renderDetails() }
        section("Result") {
            when (val result = result) {
                is StableRequestUserInputResult.Answered -> result.response.renderDetails(arguments)
                is StableRequestUserInputResult.Failure -> Detail("Error", result.message)
            }
        }
    }
}

@Composable
private fun StableToolSearchEvent.renderToolSearch() {
    ToolEvent(
        summary = arguments.toolSummary(),
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
        summary = commands.toolSummary(),
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
private fun PendingMultiAgentToolEvent.renderPendingMultiAgentTool() {
    ToolEvent(
        summary = operation.toolSummary(),
        rawName = operation.toolName,
        status = "running",
        expansionKey = callId,
        detailStyle = TextStyle.Dim,
    ) {
        section("Arguments") { operation.renderDetails(TextStyle.Dim) }
    }
}

@Composable
private fun PendingInvalidToolCall.renderInvalidToolCall() {
    ToolEvent(
        summary = "Unable to call a tool",
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
    var expanded by remember(expansionKey) { mutableStateOf(false) }
    val headerColor = toolHeaderColor(status)

    Column(modifier = Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) { isFocused, isHovered, isPressed ->
            WrappedHistoryText(
                value = "${if (expanded) "v" else ">"} $summary",
                color = headerColor,
                textStyle = when {
                    isPressed -> TextStyle.Invert
                    isFocused || isHovered -> TextStyle.Bold
                    else -> TextStyle.Unspecified
                },
            )
        }

        if (expanded) {
            rawName?.takeIf(String::isNotBlank)?.let { Detail("Tool", it, detailStyle) }
            ToolEventDetailsScope(detailStyle).content()
        }
    }
}

private fun toolHeaderColor(status: String): Color = when (status) {
    "failed" -> Color.Red
    "running", "streaming", "starting", "in_progress", "inprogress" -> Color.Green
    else -> Color.White
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
        ) { isFocused, isHovered, isPressed ->
            WrappedHistoryText(
                value = "${if (expanded) "v" else ">"} $label",
                textStyle = when {
                    isPressed -> TextStyle.Invert
                    isFocused || isHovered -> TextStyle.Bold
                    else -> detailStyle
                },
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
    var expanded by remember(expansionKey) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) { isFocused, isHovered, isPressed ->
            WrappedHistoryText(
                value = "${if (expanded) "v" else ">"} $header",
                textStyle = when {
                    isPressed -> TextStyle.Invert
                    isFocused || isHovered -> TextStyle.Bold
                    else -> headerStyle
                },
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
    WrappedHistoryText(value, textStyle)
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
            value = "${imageUrl.safeMediaReference()} · ${detail?.name?.lowercase() ?: "auto"}",
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
                    "${item.imageUrl.safeMediaReference()} · ${item.detail?.name?.lowercase() ?: "auto"}",
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
private fun RequestUserInputResponse.renderDetails(
    arguments: RequestUserInputArgs,
) {
    arguments.questions.forEach { question ->
        val answer = answers[question.id] ?: return@forEach
        Detail(
            label = "Answer ${question.header}",
            value = if (question.isSecret) "[hidden]" else answer.answers.joinToString(),
        )
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
private fun PendingMultiAgentInvocation.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    when (this) {
        is PendingMultiAgentInvocation.SpawnAgent -> arguments.renderDetails(textStyle)
        is PendingMultiAgentInvocation.SendMessage -> arguments.renderDetails(textStyle)
        is PendingMultiAgentInvocation.FollowupTask -> arguments.renderDetails(textStyle)
        is PendingMultiAgentInvocation.WaitAgent -> arguments.renderDetails(textStyle)
        is PendingMultiAgentInvocation.InterruptAgent -> arguments.renderDetails(textStyle)
        is PendingMultiAgentInvocation.ListAgents -> arguments.renderDetails(textStyle)
    }
}

@Composable
private fun SpawnAgentArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Task", taskName, textStyle)
    Detail("Message", message, textStyle)
    Detail("Fork history", forkTurns.displayName(), textStyle)
    model?.let { Detail("Model", it.value, textStyle) }
    reasoningEffort?.let { Detail("Reasoning", it.wireName, textStyle) }
    serviceTier?.let { Detail("Service tier", it.requestValue, textStyle) }
}

@Composable
private fun SendMessageArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Target", target, textStyle)
    Detail("Message", message, textStyle)
}

@Composable
private fun FollowupTaskArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Target", target, textStyle)
    Detail("Message", message, textStyle)
}

@Composable
private fun WaitAgentArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Timeout", timeoutMs?.let { "${it}ms" } ?: "default", textStyle)
}

@Composable
private fun InterruptAgentArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Target", target, textStyle)
}

@Composable
private fun ListAgentsArgs.renderDetails(
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Detail("Path prefix", pathPrefix ?: "all", textStyle)
}

@Composable
private fun StableSpawnAgentResult.renderDetails() {
    when (this) {
        is StableSpawnAgentResult.Success -> value.renderDetails()
        is StableSpawnAgentResult.Failure -> Detail("Error", message)
    }
}

@Composable
private fun SpawnAgentResult.renderDetails() {
    Detail("Agent", taskName)
    nickname?.takeIf(String::isNotBlank)?.let { Detail("Nickname", it) }
}

@Composable
private fun StableAgentDeliveryResult.renderDetails() {
    when (this) {
        is StableAgentDeliveryResult.Success -> Detail("Result", output)
        is StableAgentDeliveryResult.Failure -> Detail("Error", message)
    }
}

@Composable
private fun StableWaitAgentResult.renderDetails() {
    when (this) {
        is StableWaitAgentResult.Success -> value.renderDetails()
        is StableWaitAgentResult.Failure -> Detail("Error", message)
    }
}

@Composable
private fun WaitAgentResult.renderDetails() {
    Detail("Result", message)
    Detail("Timed out", timedOut.toString())
}

@Composable
private fun StableInterruptAgentResult.renderDetails() {
    when (this) {
        is StableInterruptAgentResult.Success -> value.renderDetails()
        is StableInterruptAgentResult.Failure -> Detail("Error", message)
    }
}

@Composable
private fun InterruptAgentResult.renderDetails() {
    Detail("Previous status", previousStatus.displayName())
}

@Composable
private fun StableListAgentsResult.renderDetails() {
    when (this) {
        is StableListAgentsResult.Success -> value.renderDetails()
        is StableListAgentsResult.Failure -> Detail("Error", message)
    }
}

@Composable
private fun ListAgentsResult.renderDetails() {
    if (agents.isEmpty()) {
        Detail("Agents", "none")
    } else {
        agents.forEach { agent -> Detail("Agent", "${agent.agentName} · ${agent.agentStatus.displayName()}") }
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
    session: UnifiedExecProcessSession?,
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
private fun activeUnifiedExecProcessSession(
    client: UnifiedExecToolClient?,
    sessionId: Int?,
): UnifiedExecProcessSession? {
    if (client == null || sessionId == null) return null
    val sessions by client.activeSessions.collectAsState()
    return sessions[sessionId]
}

@Composable
private fun UnifiedExecProcessSession?.completedForPresentation(): Boolean {
    if (this == null) return false
    val completed by this.completed.collectAsState()
    return completed
}

@Composable
private fun UnifiedExecProcessSession.renderProcessStatus(completed: Boolean) {
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

private fun StableRequestUserInputResult.status(): String = when (this) {
    is StableRequestUserInputResult.Answered -> "answered"
    is StableRequestUserInputResult.Failure -> "failed"
}

private fun ToolSearchResult.status(): String = when (this) {
    is ToolSearchResult.Success -> "succeeded"
    is ToolSearchResult.InvalidArguments -> "failed"
}

private fun StableWebSearchResult.status(): String = when (this) {
    is StableWebSearchResult.Success -> "succeeded"
    is StableWebSearchResult.Failure -> "failed"
}

private fun StableSpawnAgentResult.completedStatus(): String = when (this) {
    is StableSpawnAgentResult.Success -> "succeeded"
    is StableSpawnAgentResult.Failure -> "failed"
}

private fun StableAgentDeliveryResult.completedStatus(): String = when (this) {
    is StableAgentDeliveryResult.Success -> "succeeded"
    is StableAgentDeliveryResult.Failure -> "failed"
}

private fun StableWaitAgentResult.completedStatus(): String = when (this) {
    is StableWaitAgentResult.Success -> "succeeded"
    is StableWaitAgentResult.Failure -> "failed"
}

private fun StableInterruptAgentResult.completedStatus(): String = when (this) {
    is StableInterruptAgentResult.Success -> "succeeded"
    is StableInterruptAgentResult.Failure -> "failed"
}

private fun StableListAgentsResult.completedStatus(): String = when (this) {
    is StableListAgentsResult.Success -> "succeeded"
    is StableListAgentsResult.Failure -> "failed"
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

private fun SpawnForkMode.displayName(): String = when (this) {
    SpawnForkMode.None -> "none"
    SpawnForkMode.All -> "all"
    is SpawnForkMode.Recent -> "${turns} turns"
}

private fun MultiAgentStatus.displayName(): String = when (this) {
    MultiAgentStatus.Running -> "running"
    MultiAgentStatus.Idle -> "idle"
}

private fun MessagePhase.displayName(): String = when (this) {
    MessagePhase.Commentary -> "commentary"
    MessagePhase.FinalAnswer -> "final answer"
}

private fun LoadableToolSpec.displayName(): String = when (this) {
    is ResponsesApiTool -> name
    is ResponsesApiNamespace -> name
}

private fun qualifiedName(
    name: String,
    namespace: String?,
): String = namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name

internal fun functionToolSummary(
    name: String,
    namespace: String?,
): String = when (qualifiedName(name, namespace)) {
    "exec_command" -> "Run a command"
    "shell.run" -> "Run a command"
    "write_stdin" -> "Send input to a terminal"
    "view_image" -> "View an image"
    "image_gen.imagegen" -> "Generate an image"
    "request_user_input" -> "Ask the user for input"
    "tool_search",
    "server_tool_search",
    -> "Search available tools"

    "web.run",
    "hosted_web_search",
    -> "Search the web"

    "hosted_image_generation" -> "Generate an image"
    "spawn_agent" -> "Start an agent"
    "send_message" -> "Message an agent"
    "followup_task" -> "Continue an agent task"
    "wait_agent" -> "Wait for an agent"
    "interrupt_agent" -> "Interrupt an agent"
    "list_agents" -> "List agents"
    "update_plan" -> "Update the plan"
    "get_context_remaining" -> "Check remaining context"
    "clock.curr_time" -> "Check the current time"
    else -> qualifiedName(name, namespace)
}

private fun StableCommandExecutionAction.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String = when (this) {
    is StableCommandExecutionAction.ExecCommand -> arguments.toolSummary()
    is StableCommandExecutionAction.WriteStdin -> arguments.toolSummary(sourceArguments)
}

private fun PendingCommandExecutionAction.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String = when (this) {
    is PendingCommandExecutionAction.ExecCommand -> arguments.toolSummary()
    is PendingCommandExecutionAction.WriteStdin -> arguments.toolSummary(sourceArguments)
}

private fun ExecCommandArguments.toolSummary(): String =
    command.semanticExcerpt()
        .takeIf(String::isNotBlank)
        ?.let { command -> "Run command: $command" }
        ?: "Run a command"

private fun WriteStdinArguments.toolSummary(sourceArguments: ExecCommandArguments? = null): String {
    val command = sourceArguments
        ?.command
        ?.semanticExcerpt()
        ?.takeIf(String::isNotBlank)
    return when {
        command == null && chars.isEmpty() -> "Wait for terminal session $sessionId"
        command == null -> "Send input to terminal session $sessionId"
        chars.isEmpty() -> "Read output: $command"
        else -> "Send input: $command"
    }
}

private fun ImageGenToolArguments.toolSummary(): String = prompt.imageGenerationSummary()

private fun String.imageGenerationSummary(): String =
    semanticExcerpt()
        .takeIf(String::isNotBlank)
        ?.let { prompt -> "Generate an image: $prompt" }
        ?: "Generate an image"

private fun ViewImageToolArguments.toolSummary(): String =
    path.semanticExcerpt()
        .takeIf(String::isNotBlank)
        ?.let { path -> "View image: $path" }
        ?: "View an image"

private fun RequestUserInputArgs.toolSummary(): String =
    questions.firstOrNull()
        ?.question
        ?.semanticExcerpt()
        ?.takeIf(String::isNotBlank)
        ?.let { question -> "Ask the user: $question" }
        ?: "Ask the user for input"

private fun SearchToolCallParams.toolSummary(): String =
    query.semanticExcerpt()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Search available tools: $query" }
        ?: "Search available tools"

private fun SearchCommands.toolSummary(): String = when {
    !searchQuery.isNullOrEmpty() -> searchQuery.orEmpty().first().q.semanticExcerpt()
        .takeIf(String::isNotBlank)
        ?.let { query -> "Search the web: $query" }
        ?: "Search the web"

    !imageQuery.isNullOrEmpty() -> imageQuery.orEmpty().first().q.semanticExcerpt()
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

private fun WebSearchAction.toolSummary(): String = when (this) {
    is WebSearchAction.Search -> (query ?: queries?.firstOrNull())
        ?.semanticExcerpt()
        ?.takeIf(String::isNotBlank)
        ?.let { query -> "Search the web: $query" }
        ?: "Search the web"

    is WebSearchAction.OpenPage -> "Open a web page"
    is WebSearchAction.FindInPage -> "Find text on a web page"
    WebSearchAction.Other -> "Use web search"
}

private fun SpawnAgentArgs.toolSummary(): String = "Start agent: ${taskName.semanticExcerpt()}"

private fun SendMessageArgs.toolSummary(): String = "Message agent: ${target.semanticExcerpt()}"

private fun FollowupTaskArgs.toolSummary(): String = "Continue task for agent: ${target.semanticExcerpt()}"

private fun WaitAgentArgs.toolSummary(): String = "Wait for an agent"

private fun InterruptAgentArgs.toolSummary(): String = "Interrupt agent: ${target.semanticExcerpt()}"

private fun ListAgentsArgs.toolSummary(): String =
    pathPrefix?.semanticExcerpt()?.takeIf(String::isNotBlank)?.let { prefix -> "List agents under: $prefix" }
        ?: "List agents"

private fun PendingMultiAgentInvocation.toolSummary(): String = when (this) {
    is PendingMultiAgentInvocation.SpawnAgent -> arguments.toolSummary()
    is PendingMultiAgentInvocation.SendMessage -> arguments.toolSummary()
    is PendingMultiAgentInvocation.FollowupTask -> arguments.toolSummary()
    is PendingMultiAgentInvocation.WaitAgent -> arguments.toolSummary()
    is PendingMultiAgentInvocation.InterruptAgent -> arguments.toolSummary()
    is PendingMultiAgentInvocation.ListAgents -> arguments.toolSummary()
}

private fun String.semanticExcerpt(): String {
    val singleLine = lineSequence()
        .map { line -> line.trim() }
        .filter(String::isNotEmpty)
        .joinToString(" ")
    return singleLine.ellipsizeToTerminalWidth(MaximumSemanticSummaryColumns)
}

private fun String.displayStdin(): String =
    if (isEmpty()) "[poll]" else replace("\r", "\\r").replace("\n", "\\n")

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
private const val MaximumSemanticSummaryColumns: Int = 96
private const val Ellipsis: String = "..."
