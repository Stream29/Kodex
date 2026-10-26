package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.*

@Composable
public actual fun AgentHistoryContent(
    model: AgentHistoryViewModel, renderer: HistoryListRenderer, shellSessions: AgentShellSessionRegistry?,
) {
    val window = model.historyItems.collectAsState().value
    val load = model.loadState.collectAsState().value
    val pendingTools = model.pendingTools.collectAsState().value
    val streaming = model.streamingItem.collectAsState().value
    val follows = model.followsLatest.collectAsState().value
    val effect = model.pendingScrollEffect.collectAsState().value
    val pending = pendingTools.asReversed()
    val newerKey = when {
        window.hasNewer && load == AgentHistoryLoadState.Ready -> NewerHistoryDemandKey
        load == AgentHistoryLoadState.LoadingNewer -> NewerHistoryLoadingKey
        else -> null
    }
    val prefix = (if (streaming == null) 0 else 1) + pending.size + if (newerKey == null) 0 else 1
    val marker = when (load) {
        AgentHistoryLoadState.Initializing -> HistoryMarker.Loading
        is AgentHistoryLoadState.Failed -> HistoryMarker.Failure
        AgentHistoryLoadState.Ready -> if (window.size == 0 && !window.hasOlder && !window.hasNewer) HistoryMarker.Empty else null
        else -> null
    }
    val markerKey = marker?.let { HistoryMarkerKey(window.generation, it) }
    val latestKey = streaming?.historyIdentity()
        ?: pending.firstOrNull()?.let { PendingHistoryKey(window.generation, it.historyIdentity(0)) }
        ?: newerKey
        ?: window.takeIf { it.size > 0 }?.peek(0)
        ?: markerKey
        ?: if (load == AgentHistoryLoadState.LoadingOlder) OlderHistoryLoadingKey else null
    renderer.List(model, window, follows, effect, prefix, latestKey) { list ->
        streaming?.let { output ->
            list.Row(output.historyIdentity(), output.historyContentType()) {
                HistoryStreamingContent(output, renderer) { renderer.streamingContentChanged(model) }
            }
        }
        pending.forEachIndexed { position, event ->
            list.Row(PendingHistoryKey(window.generation, event.historyIdentity(position)), event.historyContentType()) {
                HistoryEventContent(event, renderer, shellSessions)
            }
        }
        newerKey?.let { list.Row(it, HistoryContentType.Marker) {} }
        list.Committed(window, { it.historyContentType() }) { item ->
            renderer.StoredItem(item, window.generation, shellSessions)
        }
        if (window.hasOlder && load == AgentHistoryLoadState.Ready) {
            list.Row(OlderHistoryDemandKey, HistoryContentType.Marker) {}
        }
        when (load) {
            AgentHistoryLoadState.LoadingOlder -> list.Row(OlderHistoryLoadingKey, HistoryContentType.Marker) {}
            AgentHistoryLoadState.LoadingNewer -> Unit
            AgentHistoryLoadState.Initializing -> list.Row(checkNotNull(markerKey), HistoryContentType.Marker) {
                renderer.Marker("Loading history…", false)
            }
            is AgentHistoryLoadState.Failed -> list.Row(checkNotNull(markerKey), HistoryContentType.Marker) {
                renderer.Marker("History error: ${load.message}", true)
            }
            AgentHistoryLoadState.Ready -> if (streaming == null && pending.isEmpty() && marker == HistoryMarker.Empty) {
                list.Row(checkNotNull(markerKey), HistoryContentType.Marker) {
                    renderer.Marker("No conversation history items", false)
                }
            }
        }
    }
}

private fun HistoryItemViewModel.historyContentType(): HistoryContentType = when (this) {
    is MessageHistoryItemViewModel -> HistoryContentType.Message
    is ReasoningHistoryItemViewModel -> HistoryContentType.Reasoning
    is ToolHistoryItemViewModel, is RequestUserInputHistoryItemViewModel,
    is SuggestSubagentTaskHistoryItemViewModel, is PlanUpdateHistoryItemViewModel -> HistoryContentType.CompletedTool
    is PatchHistoryItemViewModel -> HistoryContentType.Patch
    is ContextCompactionHistoryItemViewModel -> HistoryContentType.Context
    is WorkGroupHistoryItemViewModel -> HistoryContentType.WorkGroup
}

private fun UnstableCleanEvent.historyContentType(): HistoryContentType = when (this) {
    is PendingPatchToolEvent -> HistoryContentType.Patch
    else -> HistoryContentType.PendingTool
}

private fun UnstableCleanEvent.historyIdentity(position: Int): String = when (this) {
    is PendingToolEvent -> "call:$callId"
    is PendingServerToolSearch -> "server:${call.id?.value ?: position}"
}

private fun HistoryStreamingItem.historyIdentity(): Any = when (this) {
    HistoryStreamingItem.Started -> StreamingStartedHistoryKey
    is HistoryStreamingItem.Output -> events
    HistoryStreamingItem.Compacting -> CompactingHistoryKey
}

private fun HistoryStreamingItem.historyContentType(): HistoryContentType = when (this) {
    HistoryStreamingItem.Started, HistoryStreamingItem.Compacting -> HistoryContentType.StreamingStatus
    is HistoryStreamingItem.Output -> when (kind) {
        HistoryStreamingKind.Message, HistoryStreamingKind.AgentMessage -> HistoryContentType.StreamingMessage
        HistoryStreamingKind.Reasoning -> HistoryContentType.StreamingReasoning
        HistoryStreamingKind.ToolCall, HistoryStreamingKind.Unknown -> HistoryContentType.StreamingTool
    }
}

private data class PendingHistoryKey(val generation: Long, val identity: String)
private data class HistoryMarkerKey(val generation: Long, val marker: HistoryMarker)
private data object OlderHistoryDemandKey
private data object OlderHistoryLoadingKey
private data object NewerHistoryDemandKey
private data object NewerHistoryLoadingKey
private data object StreamingStartedHistoryKey
private data object CompactingHistoryKey
private enum class HistoryMarker { Loading, Failure, Empty }
private enum class HistoryContentType {
    StreamingStatus, StreamingMessage, StreamingReasoning, StreamingTool, Message, Reasoning,
    CompletedTool, PendingTool, Patch, Context, WorkGroup, Marker,
}
