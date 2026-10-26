package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
public actual fun HistoryStreamingTextContent(
    kind: HistoryStreamingKind,
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
    heading: @Composable (String, supporting: Boolean) -> Unit,
    body: @Composable (String, supporting: Boolean) -> Unit,
) {
    require(kind == HistoryStreamingKind.Message || kind == HistoryStreamingKind.AgentMessage ||
        kind == HistoryStreamingKind.Reasoning)
    val snapshot = rememberStreamingSnapshot(events, onContentChange)
    val supporting = kind == HistoryStreamingKind.Reasoning
    val header = when (kind) {
        HistoryStreamingKind.Message -> "Assistant responding"
        HistoryStreamingKind.AgentMessage -> (snapshot.item as? ResponseItem.AgentMessage)
            ?.let { "Sending message: ${it.author} → ${it.recipient}" } ?: "Sending an agent message"
        HistoryStreamingKind.Reasoning -> "Thinking"
    }
    UiColumn {
        heading(header, supporting)
        val parts = if (supporting) snapshot.reasoningSummaryParts else snapshot.messageParts
        parts.forEach { body(it.text, supporting) }
    }
}

@Composable
public actual fun rememberStreamingSnapshot(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
): StreamingResponseSnapshot {
    var snapshot by remember(events) { mutableStateOf(StreamingResponseSnapshot()) }
    val latestOnContentChange = rememberUpdatedState(onContentChange)
    LaunchedEffect(events) {
        coroutineScope {
            val pendingSnapshots = Channel<StreamingResponseSnapshot>(Channel.CONFLATED)
            launch {
                var accumulated = StreamingResponseSnapshot()
                events.collect { event ->
                    val updated = accumulated.reduce(event)
                    if (updated != accumulated) {
                        accumulated = updated
                        pendingSnapshots.trySend(updated)
                    }
                }
            }
            while (isActive) {
                delay(50)
                val updated = pendingSnapshots.tryReceive().getOrNull() ?: continue
                if (updated != snapshot) {
                    latestOnContentChange.value()
                    snapshot = updated
                }
            }
        }
    }
    return snapshot
}

private fun StreamingResponseSnapshot.reduce(event: ResponsesStreamEvent): StreamingResponseSnapshot = when (event) {
    is ResponsesStreamEvent.OutputItemAdded -> copy(
        item = event.item,
        messageParts = event.item.initialMessageParts(),
        reasoningSummaryParts = event.item.initialReasoningSummaryParts(),
        toolInputParts = event.item.initialToolInputParts(),
    )
    is ResponsesStreamEvent.ContentPartAdded -> event.part.streamingTextOrNull()
        ?.let { copy(messageParts = messageParts.replace(event.contentIndex.toString(), it)) } ?: this
    is ResponsesStreamEvent.ContentPartDone -> event.part.streamingTextOrNull()
        ?.let { copy(messageParts = messageParts.replace(event.contentIndex.toString(), it)) } ?: this
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
        ?.let { copy(reasoningSummaryParts = reasoningSummaryParts.replace(event.summaryIndex.toString(), it.text)) } ?: this
    is ResponsesStreamEvent.ToolCallInputDelta -> {
        val key = item.streamingToolInputKey() ?: event.callId ?: event.itemId
        if (key == null) this else copy(toolInputParts = toolInputParts.append(key, event.delta))
    }
    is ResponsesStreamEvent.WebSearchCallInProgress -> copy(webSearchStatus = "starting")
    is ResponsesStreamEvent.WebSearchCallSearching -> copy(webSearchStatus = "searching")
    is ResponsesStreamEvent.WebSearchCallCompleted -> copy(webSearchStatus = "completed")
    is ResponsesStreamEvent.Other -> copy(unknownPayload = event.payload)
    is ResponsesStreamEvent.ReasoningTextDelta,
    is ResponsesStreamEvent.ReasoningTextDone,
    is ResponsesStreamEvent.Created,
    is ResponsesStreamEvent.InProgress,
    is ResponsesStreamEvent.Metadata,
    is ResponsesStreamEvent.OutputItemDone,
    is ResponsesStreamEvent.Completed,
    is ResponsesStreamEvent.Failed,
    is ResponsesStreamEvent.Incomplete -> this
}

private fun List<StreamingTextPart>.append(key: String, delta: String): List<StreamingTextPart> =
    replace(key, (firstOrNull { it.key == key }?.text ?: "") + delta)

private fun List<StreamingTextPart>.replace(key: String, text: String): List<StreamingTextPart> {
    val replacement = StreamingTextPart(key, text)
    val index = indexOfFirst { it.key == key }
    return if (index < 0) this + replacement else mapIndexed { at, part -> if (at == index) replacement else part }
}

private fun ContentItem.streamingTextOrNull(): String? = when (this) {
    is ContentItem.InputText -> text
    is ContentItem.OutputText -> text
    is ContentItem.InputImage -> "[image]"
}

private fun ResponseItem?.initialMessageParts(): List<StreamingTextPart> = when (this) {
    is ResponseItem.Message -> content.mapIndexed { index, part ->
        StreamingTextPart(index.toString(), part.streamingTextOrNull().orEmpty())
    }.filter { it.text.isNotBlank() }
    is ResponseItem.AgentMessage -> content.mapIndexed { index, part ->
        StreamingTextPart(index.toString(), when (part) {
            is AgentMessageInputContent.InputText -> part.text
            is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
        })
    }.filter { it.text.isNotBlank() }
    else -> emptyList()
}

private fun ResponseItem?.initialReasoningSummaryParts(): List<StreamingTextPart> =
    (this as? ResponseItem.Reasoning)?.summary.orEmpty().mapIndexedNotNull { index, part ->
        (part as? ReasoningItemReasoningSummary.SummaryText)?.text?.takeIf(String::isNotBlank)
            ?.let { StreamingTextPart(index.toString(), it) }
    }

private fun ResponseItem?.initialToolInputParts(): List<StreamingTextPart> {
    val input = when (this) {
        is ResponseItem.FunctionCall -> arguments
        is ResponseItem.CustomToolCall -> input
        is ResponseItem.ClientToolSearchCall -> arguments.toString()
        is ResponseItem.ServerToolSearchCall -> arguments.toString()
        else -> null
    }?.takeIf(String::isNotBlank) ?: return emptyList()
    val key = streamingToolInputKey() ?: return emptyList()
    return listOf(StreamingTextPart(key, input))
}

private fun ResponseItem?.streamingToolInputKey(): String? = when (this) {
    is ResponseItem.ToolCall -> callId
    is ResponseItem.LocalShellCall -> callId ?: id?.value
    is ResponseItem.ServerToolSearchCall -> id?.value
    is ResponseItem.WebSearchCall -> id?.value
    is ResponseItem.ImageGenerationCall -> id?.value
    else -> null
}
