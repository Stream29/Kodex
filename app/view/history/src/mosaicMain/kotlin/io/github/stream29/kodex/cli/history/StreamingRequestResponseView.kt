package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow

/** Renders the one active operation that belongs after the persisted history tail. */
@Composable
internal fun HistoryStreamingItem.renderTransientTail(
    onContentChange: () -> Unit = {},
) {
    when (this) {
        HistoryStreamingItem.Started -> StreamingStatusTail("Starting response…")
        HistoryStreamingItem.Compacting -> StreamingStatusTail("Compacting context…")
        is HistoryStreamingItem.Output -> when (kind) {
            HistoryStreamingKind.Message -> StreamingMessageTail(events, onContentChange)
            HistoryStreamingKind.AgentMessage -> StreamingAgentMessageTail(events, onContentChange)
            HistoryStreamingKind.Reasoning -> StreamingReasoningTail(events, onContentChange)
            HistoryStreamingKind.ToolCall -> StreamingToolCallTail(events, onContentChange)
            HistoryStreamingKind.Unknown -> StreamingUnknownTail(events, onContentChange)
        }
    }
}

@Composable
private fun StreamingMessageTail(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    StreamingTextTail(
        header = "Assistant · streaming",
        parts = snapshot.messageParts,
        headerStyle = TextStyle.Bold,
    )
}

@Composable
private fun StreamingAgentMessageTail(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    val header = (snapshot.item as? ResponseItem.AgentMessage)
        ?.let { item -> "${item.author} → ${item.recipient} · streaming" }
        ?: "Agent message · streaming"
    StreamingTextTail(
        header = header,
        parts = snapshot.messageParts,
        headerStyle = TextStyle.Bold,
    )
}

@Composable
private fun StreamingReasoningTail(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    StreamingTextTail(
        header = "Thinking · streaming",
        parts = snapshot.reasoningSummaryParts,
        headerStyle = TextStyle.Dim,
        detailStyle = TextStyle.Dim,
    )
}

@Composable
private fun StreamingToolCallTail(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    val presentation = snapshot.toolPresentation()
    ToolEvent(
        summary = presentation.summary,
        rawName = presentation.rawName,
        status = presentation.status,
        expansionKey = events,
        detailStyle = TextStyle.Dim,
    ) {
        section("Input") {
            snapshot.toolInputParts.forEach { part ->
                WrappedHistoryText("Input: ${part.text}", TextStyle.Dim)
            }
        }
    }
}

@Composable
private fun StreamingUnknownTail(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
) {
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    StreamingStatusTail(snapshot.item?.unknownHeader() ?: "Receiving response item…")
}

@Composable
private fun StreamingStatusTail(value: String) {
    WrappedHistoryText(value, TextStyle.Dim)
}

@Composable
private fun StreamingTextTail(
    header: String,
    parts: List<StreamingTextPart>,
    headerStyle: TextStyle,
    detailStyle: TextStyle = TextStyle.Unspecified,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        WrappedHistoryText(header, headerStyle)
        parts.forEach { part -> WrappedHistoryText(part.text, detailStyle) }
    }
}

@Composable
private fun rememberStreamingSnapshot(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
): StreamingResponseSnapshot {
    var snapshot by remember(events) { mutableStateOf(StreamingResponseSnapshot()) }
    val latestOnContentChange = rememberUpdatedState(onContentChange)
    LaunchedEffect(events) {
        events.collect { event ->
            val updatedSnapshot = snapshot.reduce(event)
            if (updatedSnapshot != snapshot) {
                latestOnContentChange.value()
                snapshot = updatedSnapshot
            }
        }
    }
    return snapshot
}

/** Local replay accumulator; it is deliberately not a cross-layer presentation model. */
private data class StreamingResponseSnapshot(
    val item: ResponseItem? = null,
    val messageParts: List<StreamingTextPart> = emptyList(),
    val reasoningSummaryParts: List<StreamingTextPart> = emptyList(),
    val toolInputParts: List<StreamingTextPart> = emptyList(),
    val webSearchStatus: String? = null,
) {
    fun reduce(event: ResponsesStreamEvent): StreamingResponseSnapshot = when (event) {
        is ResponsesStreamEvent.OutputItemAdded -> copy(
            item = event.item,
            // The item already carries any prefix emitted before the first delta.
            // Keep it under the protocol's actual indexes so later deltas extend
            // the matching part instead of creating a second presentation row.
            messageParts = event.item.initialMessageParts(),
            reasoningSummaryParts = event.item.initialReasoningSummaryParts(),
            toolInputParts = event.item.initialToolInputParts(),
        )

        is ResponsesStreamEvent.ContentPartAdded -> event.part.streamingTextOrNull()
            ?.let { text ->
                copy(messageParts = messageParts.replace(event.contentIndex.toString(), text))
            }
            ?: this

        is ResponsesStreamEvent.ContentPartDone -> event.part.streamingTextOrNull()
            ?.let { text ->
                copy(messageParts = messageParts.replace(event.contentIndex.toString(), text))
            }
            ?: this

        is ResponsesStreamEvent.OutputTextDelta -> copy(
            messageParts = messageParts.append(event.contentIndex.toString(), event.delta),
        )

        is ResponsesStreamEvent.OutputTextDone -> copy(
            messageParts = messageParts.replace(event.contentIndex.toString(), event.text),
        )

        is ResponsesStreamEvent.ReasoningSummaryTextDelta -> copy(
            reasoningSummaryParts = reasoningSummaryParts.append(event.summaryIndex.toString(), event.delta),
        )

        is ResponsesStreamEvent.ReasoningSummaryTextDone -> copy(
            reasoningSummaryParts = reasoningSummaryParts.replace(event.summaryIndex.toString(), event.text),
        )

        is ResponsesStreamEvent.ReasoningSummaryPartAdded -> (event.part as? ReasoningItemReasoningSummary.SummaryText)
            ?.let { summary ->
                copy(
                    reasoningSummaryParts = reasoningSummaryParts.replace(
                        event.summaryIndex.toString(),
                        summary.text,
                    ),
                )
            }
            ?: this

        is ResponsesStreamEvent.ToolCallInputDelta -> {
            val key = item.streamingToolInputKey() ?: event.callId ?: event.itemId
            if (key == null) this else copy(toolInputParts = toolInputParts.append(key, event.delta))
        }

        is ResponsesStreamEvent.WebSearchCallInProgress -> copy(webSearchStatus = "starting")
        is ResponsesStreamEvent.WebSearchCallSearching -> copy(webSearchStatus = "searching")
        is ResponsesStreamEvent.WebSearchCallCompleted -> copy(webSearchStatus = "completed")

        // Full reasoning and opaque protocol payloads must never enter the terminal timeline.
        is ResponsesStreamEvent.ReasoningTextDelta,
        is ResponsesStreamEvent.ReasoningTextDone,
        is ResponsesStreamEvent.Created,
        is ResponsesStreamEvent.InProgress,
        is ResponsesStreamEvent.Metadata,
        is ResponsesStreamEvent.OutputItemDone,
        is ResponsesStreamEvent.Completed,
        is ResponsesStreamEvent.Failed,
        is ResponsesStreamEvent.Incomplete,
        is ResponsesStreamEvent.Other,
        -> this
    }

    fun toolPresentation(): StreamingToolPresentation = item.streamingToolPresentation(webSearchStatus)
}

private data class StreamingToolPresentation(
    val summary: String,
    val rawName: String?,
    val status: String,
)

private data class StreamingTextPart(
    val key: String,
    val text: String,
)

private fun List<StreamingTextPart>.append(
    key: String,
    delta: String,
): List<StreamingTextPart> {
    val current = firstOrNull { part -> part.key == key }
    return replace(key, (current?.text ?: "") + delta)
}

private fun List<StreamingTextPart>.replace(
    key: String,
    text: String,
): List<StreamingTextPart> {
    val replacement = StreamingTextPart(key = key, text = text)
    val index = indexOfFirst { part -> part.key == key }
    return if (index < 0) {
        this + replacement
    } else {
        mapIndexed { currentIndex, part -> if (currentIndex == index) replacement else part }
    }
}

private fun ContentItem.streamingTextOrNull(): String? = when (this) {
    is ContentItem.InputText -> text
    is ContentItem.OutputText -> text
    is ContentItem.InputImage -> "[image]"
}

private fun ResponseItem?.initialMessageParts(): List<StreamingTextPart> = when (this) {
    is ResponseItem.Message -> content.mapIndexed { index, content ->
        StreamingTextPart(index.toString(), content.streamingTextOrNull().orEmpty())
    }.filter { part -> part.text.isNotBlank() }

    is ResponseItem.AgentMessage -> content.mapIndexed { index, content ->
        val text = when (content) {
            is AgentMessageInputContent.InputText -> content.text
            is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
        }
        StreamingTextPart(index.toString(), text)
    }.filter { part -> part.text.isNotBlank() }

    else -> emptyList()
}

private fun ResponseItem?.initialReasoningSummaryParts(): List<StreamingTextPart> =
    (this as? ResponseItem.Reasoning)
        ?.summary
        ?.mapIndexedNotNull { index, summary ->
            (summary as? ReasoningItemReasoningSummary.SummaryText)
                ?.text
                ?.takeIf(String::isNotBlank)
                ?.let { text -> StreamingTextPart(index.toString(), text) }
        }
        .orEmpty()

private fun ResponseItem?.initialToolInputParts(): List<StreamingTextPart> {
    val input = when (this) {
        is ResponseItem.FunctionCall -> arguments
        is ResponseItem.CustomToolCall -> input
        is ResponseItem.ClientToolSearchCall -> arguments.toString()
        is ResponseItem.ServerToolSearchCall -> arguments.toString()
        else -> null
    }?.takeIf(String::isNotBlank) ?: return emptyList()
    val key = streamingToolInputKey() ?: return emptyList()
    return listOf(StreamingTextPart(key = key, text = input))
}

private fun ResponseItem?.streamingToolPresentation(
    webSearchStatus: String?,
): StreamingToolPresentation = when (this) {
    is ResponseItem.FunctionCall -> StreamingToolPresentation(
        summary = functionToolSummary(name, namespace),
        rawName = qualifiedStreamingName(name, namespace),
        status = "streaming",
    )

    is ResponseItem.CustomToolCall -> StreamingToolPresentation(
        summary = functionToolSummary(name, namespace),
        rawName = qualifiedStreamingName(name, namespace),
        status = status ?: "streaming",
    )

    is ResponseItem.ClientToolSearchCall -> StreamingToolPresentation(
        summary = "Search available tools",
        rawName = "tool_search",
        status = status ?: "streaming",
    )

    is ResponseItem.ServerToolSearchCall -> StreamingToolPresentation(
        summary = "Load tools from the server",
        rawName = "server_tool_search",
        status = status ?: "streaming",
    )

    is ResponseItem.LocalShellCall -> StreamingToolPresentation(
        summary = "Run a command",
        rawName = "shell",
        status = status.name.lowercase(),
    )

    is ResponseItem.WebSearchCall -> StreamingToolPresentation(
        summary = "Search the web",
        rawName = "hosted_web_search",
        status = webSearchStatus ?: status ?: "streaming",
    )

    is ResponseItem.ImageGenerationCall -> StreamingToolPresentation(
        summary = "Generate an image",
        rawName = "hosted_image_generation",
        status = status,
    )

    is ResponseItem.FunctionCallOutput -> StreamingToolPresentation(
        summary = "Receive a tool result",
        rawName = null,
        status = "streaming",
    )

    is ResponseItem.McpToolCallOutput -> StreamingToolPresentation(
        summary = "Receive an MCP tool result",
        rawName = null,
        status = "streaming",
    )

    is ResponseItem.CustomToolCallOutput -> StreamingToolPresentation(
        summary = "Receive a tool result",
        rawName = null,
        status = "streaming",
    )

    is ResponseItem.ClientToolSearchOutput -> StreamingToolPresentation(
        summary = "Receive available tools",
        rawName = null,
        status = status,
    )

    is ResponseItem.ServerToolSearchOutput -> StreamingToolPresentation(
        summary = "Receive available tools",
        rawName = null,
        status = status,
    )

    is ResponseItem.AdditionalTools -> StreamingToolPresentation(
        summary = "Update the available tool catalog",
        rawName = null,
        status = "streaming",
    )

    else -> StreamingToolPresentation(
        summary = "Run a tool",
        rawName = null,
        status = "streaming",
    )
}

private fun ResponseItem?.streamingToolInputKey(): String? = when (this) {
    is ResponseItem.ToolCall -> callId
    is ResponseItem.LocalShellCall -> callId ?: id?.value
    is ResponseItem.ServerToolSearchCall -> id?.value
    is ResponseItem.WebSearchCall -> id?.value
    is ResponseItem.ImageGenerationCall -> id?.value
    else -> null
}

private fun ResponseItem.unknownHeader(): String = when (this) {
    is ResponseItem.Message -> "Assistant · streaming"
    is ResponseItem.AgentMessage -> "$author → $recipient · streaming"
    is ResponseItem.Reasoning -> "Thinking · streaming"
    else -> streamingToolPresentation(webSearchStatus = null).let { presentation ->
        presentation.summary
    }
}

private fun qualifiedStreamingName(name: String, namespace: String?): String =
    namespace?.takeIf(String::isNotBlank)?.let { value -> "$value.$name" } ?: name
