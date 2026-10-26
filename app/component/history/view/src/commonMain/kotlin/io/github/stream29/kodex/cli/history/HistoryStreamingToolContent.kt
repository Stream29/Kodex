package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.stream29.kodex.cli.history.HistoryTextStyle as TextStyle
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow

@Composable
public actual fun HistoryStreamingContent(
    item: HistoryStreamingItem, renderer: HistoryContentRenderer, onContentChange: () -> Unit,
) {
    CompositionLocalProvider(LocalHistoryRenderer provides renderer) { with(item) { when (this) {
        HistoryStreamingItem.Started -> WrappedHistoryText("Starting response…", TextStyle.Dim)
        HistoryStreamingItem.Compacting -> WrappedHistoryText("Compacting context…", TextStyle.Dim)
        is HistoryStreamingItem.Output -> when (kind) {
            HistoryStreamingKind.Message,
            HistoryStreamingKind.AgentMessage,
            HistoryStreamingKind.Reasoning -> HistoryStreamingTextContent(
                kind, events, onContentChange,
                heading = { text, supporting ->
                    WrappedHistoryText(text, if (supporting) TextStyle.Dim else TextStyle.Bold)
                },
                body = { text, supporting ->
                    WrappedHistoryText(text, if (supporting) TextStyle.Dim else TextStyle.Normal)
                },
            )
            HistoryStreamingKind.ToolCall -> StreamingToolCallTail(events, onContentChange)
            HistoryStreamingKind.Unknown -> StreamingUnknownTail(events, onContentChange)
        }
    } } }
}

@Composable
private fun StreamingToolCallTail(events: SharedFlow<ResponsesStreamEvent>, onContentChange: () -> Unit) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    val presentation = snapshot.item.streamingToolPresentation(snapshot.webSearchStatus)
    ToolEvent(
        summary = presentation.summary,
        rawName = presentation.rawName,
        status = presentation.status,
        expansionKey = events,
        detailStyle = TextStyle.Dim,
    ) {
        section("Input") {
            snapshot.toolInputParts.forEach { part -> WrappedHistoryText("Input: ${part.text}", TextStyle.Dim) }
        }
    }
}

@Composable
private fun StreamingUnknownTail(events: SharedFlow<ResponsesStreamEvent>, onContentChange: () -> Unit) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    ToolEvent(
        summary = "Unknown",
        rawName = null,
        status = "streaming",
        expansionKey = events,
        detailStyle = TextStyle.Dim,
    ) {
        WrappedHistoryText(snapshot.unknownPayload?.toString() ?: "{}", TextStyle.Dim)
    }
}

private data class StreamingToolPresentation(val summary: String, val rawName: String?, val status: String)

private fun ResponseItem?.streamingToolPresentation(webSearchStatus: String?): StreamingToolPresentation = when (this) {
    is ResponseItem.FunctionCall -> StreamingToolPresentation(
        functionToolOngoingSummary(name, namespace), qualifiedStreamingName(name, namespace), "streaming",
    )
    is ResponseItem.CustomToolCall -> StreamingToolPresentation(
        customToolOngoingSummary(name, namespace, input), qualifiedStreamingName(name, namespace), status ?: "streaming",
    )
    is ResponseItem.ClientToolSearchCall -> StreamingToolPresentation(
        "Searching available tools", "tool_search", status ?: "streaming",
    )
    is ResponseItem.ServerToolSearchCall -> StreamingToolPresentation(
        arguments.ongoingServerToolSearchSummary(), "server_tool_search", status ?: "streaming",
    )
    is ResponseItem.LocalShellCall -> StreamingToolPresentation("Running a command", "shell", status.name.lowercase())
    is ResponseItem.WebSearchCall -> StreamingToolPresentation(
        "Searching the web", "hosted_web_search", webSearchStatus ?: status ?: "streaming",
    )
    is ResponseItem.ImageGenerationCall -> StreamingToolPresentation("Generating an image", "hosted_image_generation", status)
    is ResponseItem.FunctionCallOutput -> StreamingToolPresentation("Receiving a tool result", null, "streaming")
    is ResponseItem.McpToolCallOutput -> StreamingToolPresentation("Receiving an MCP tool result", null, "streaming")
    is ResponseItem.CustomToolCallOutput -> StreamingToolPresentation("Receiving a tool result", null, "streaming")
    is ResponseItem.ClientToolSearchOutput -> StreamingToolPresentation("Receiving available tools", null, status)
    is ResponseItem.ServerToolSearchOutput -> StreamingToolPresentation("Receiving available tools", null, status)
    is ResponseItem.AdditionalTools -> StreamingToolPresentation("Updating the available tool catalog", null, "streaming")
    else -> StreamingToolPresentation("Running a tool", null, "streaming")
}

private fun qualifiedStreamingName(name: String, namespace: String?): String =
    namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name
