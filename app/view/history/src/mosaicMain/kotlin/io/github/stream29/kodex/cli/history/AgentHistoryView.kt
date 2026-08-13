package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListLayoutInfo
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.AgentStreamState
import io.github.stream29.kodex.app.agent.contract.AgentStreamTail
import io.github.stream29.kodex.app.history.contract.AgentHistoryEdgeState
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadRequest
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** Renders one Agent history model. */
@Composable
public fun AgentHistoryView(
    agentId: String,
    model: AgentHistoryViewModel,
    stream: AgentStreamState,
    uiState: AgentHistoryUiState,
    shellSessions: AgentShellSessionRegistry,
    onOpenEntryContextMenu: ((
        generation: Long,
        storageIndex: Int,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)? = null,
) {
    val listState = uiState.listState
    val scrollInteractionSource = uiState.interactionSource
    val entryFocusRequesters = remember(agentId) {
        mutableMapOf<StoredHistoryKey, FocusRequester>()
    }
    var window by remember(model, agentId) { mutableStateOf(model.window.value) }
    val transientTailCount = if (stream.tail == null) 0 else 1
    HistoryPagingFocusEffect(
        listState = listState,
        interactionSource = scrollInteractionSource,
        entryFocusRequesters = entryFocusRequesters,
    )
    HistoryFollowLatestEffect(uiState)

    LaunchedEffect(model, agentId, uiState) {
        model.window.collect { updatedWindow ->
            if (updatedWindow != window) {
                uiState.requestLatestForContentChange()
                window = updatedWindow
            }
        }
    }
    LaunchedEffect(stream.revision, uiState) {
        uiState.requestLatestForContentChange()
    }

    LaunchedEffect(
        model,
        agentId,
        listState,
        window.generation,
        transientTailCount,
        stream.pendingEvents.size,
        window.entries.size,
        window.olderEdge,
    ) {
        val initialOlderCursor = window.olderEdge.loadableCursor()
        if (
            window.entries.isEmpty() ||
            initialOlderCursor == null
        ) {
            return@LaunchedEffect
        }

        val loadBoundary = (
            transientTailCount +
                stream.pendingEvents.size +
                window.entries.lastIndex -
                HistoryPrefetchDistance
            ).coerceAtLeast(0)
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { item ->
                item.index >= loadBoundary
            }
        }
            .distinctUntilChanged()
            .filter { nearOlderBoundary -> nearOlderBoundary }
            .collect {
                val cursor = window.olderEdge.loadableCursor() ?: return@collect
                model.request(AgentHistoryLoadRequest(cursor, HistoryBatchSize))
            }
    }

    key(agentId) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            interactionSource = scrollInteractionSource,
            keyboardPageSize = { viewportSize -> (viewportSize / 2).coerceAtLeast(1) },
        ) {
            stream.tail?.let { tail ->
                item(
                    key = StreamingHistoryKey(
                        agentId = agentId,
                        identity = tail.historyIdentity(),
                    ),
                    contentType = tail.historyContentType(),
                ) {
                    tail.renderTransientTail(
                        onContentChange = uiState::requestLatestForContentChange,
                    )
                }
            }

            val pending = stream.pendingEvents.asReversed()
            items(
                count = pending.size,
                key = { position ->
                    PendingHistoryKey(
                        agentId = agentId,
                        generation = window.generation,
                        identity = pending[position].historyIdentity(position),
                    )
                },
                contentType = { position -> pending[position].historyContentType() },
            ) { position ->
                pending[position].render(shellSessions)
            }

            items(
                items = window.entries,
                key = { entry ->
                    StoredHistoryKey(
                        agentId = agentId,
                        generation = window.generation,
                        storageIndex = entry.key.primaryStorageIndex,
                    )
                },
                contentType = { entry -> entry.event.historyContentType() },
            ) { entry ->
                val entryKey = StoredHistoryKey(
                    agentId = agentId,
                    generation = window.generation,
                    storageIndex = entry.key.primaryStorageIndex,
                )
                val focusRequester = remember(entryKey) { FocusRequester() }
                DisposableEffect(entryKey, focusRequester) {
                    entryFocusRequesters[entryKey] = focusRequester
                    onDispose {
                        if (entryFocusRequesters[entryKey] === focusRequester) {
                            entryFocusRequesters.remove(entryKey)
                        }
                    }
                }
                StoredHistoryEntry(
                    entry = entry,
                    generation = window.generation,
                    focusRequester = focusRequester,
                    shellSessions = shellSessions,
                    onOpenContextMenu = onOpenEntryContextMenu,
                )
            }

            if (
                window.status is AgentHistoryWindowStatus.Initializing ||
                window.olderEdge is AgentHistoryEdgeState.Loading
            ) {
                item(
                    key = HistoryMarkerKey(
                        agentId = agentId,
                        generation = window.generation,
                        marker = HistoryMarker.Loading,
                    ),
                    contentType = HistoryContentType.Marker,
                ) {
                    HistoryMarkerText("Loading history…")
                }
            }

            val failureMessage = when (val status = window.status) {
                is AgentHistoryWindowStatus.Failed -> status.message
                else -> (window.olderEdge as? AgentHistoryEdgeState.Failed)?.message
            }
            failureMessage?.let {
                item(
                    key = HistoryMarkerKey(
                        agentId = agentId,
                        generation = window.generation,
                        marker = HistoryMarker.Failure,
                    ),
                    contentType = HistoryContentType.Marker,
                ) {
                    HistoryMarkerText("History error: $it")
                }
            }

            if (
                stream.tail == null &&
                window.entries.isEmpty() &&
                stream.pendingEvents.isEmpty() &&
                window.status !is AgentHistoryWindowStatus.Initializing &&
                window.olderEdge !is AgentHistoryEdgeState.Loading &&
                failureMessage == null
            ) {
                item(
                    key = HistoryMarkerKey(
                        agentId = agentId,
                        generation = window.generation,
                        marker = HistoryMarker.Empty,
                    ),
                    contentType = HistoryContentType.Marker,
                ) {
                    HistoryMarkerText("No committed conversation items")
                }
            }
        }
    }
}

@Composable
internal fun HistoryFollowLatestEffect(uiState: AgentHistoryUiState) {
    LaunchedEffect(uiState) {
        snapshotFlow {
            uiState.followsLatest to uiState.listState.canScrollForward
        }.collect {
            uiState.reconcileLayout()
        }
    }
}

@Composable
internal fun HistoryPagingFocusEffect(
    listState: LazyListState,
    interactionSource: MutableScrollInteractionSource,
    entryFocusRequesters: Map<StoredHistoryKey, FocusRequester>,
) {
    LaunchedEffect(listState, interactionSource) {
        interactionSource.interactions
            .filter { interaction ->
                interaction.source == ScrollInputSource.Keyboard &&
                    interaction.orientation == ScrollOrientation.Vertical
            }
            .collectLatest { interaction ->
                val expectedAnchorIndex = listState.firstVisibleItemIndex
                val expectedAnchorOffset = listState.firstVisibleItemScrollOffset
                val layoutInfo = snapshotFlow { listState.layoutInfo }
                    .first { layout ->
                        layout.matchesAnchor(
                            index = expectedAnchorIndex,
                            scrollOffset = expectedAnchorOffset,
                        )
                    }
                val targetKey = layoutInfo.historyPageFocusKey(
                    towardTop = interaction.consumedDelta < 0,
                ) ?: return@collectLatest

                entryFocusRequesters[targetKey]?.requestFocus()
            }
    }
}

private fun LazyListLayoutInfo.matchesAnchor(
    index: Int,
    scrollOffset: Int,
): Boolean {
    val firstVisibleItem = visibleItemsInfo.firstOrNull() ?: return false
    return firstVisibleItem.index == index &&
        firstVisibleItem.offset == viewportStartOffset - scrollOffset
}

internal fun LazyListLayoutInfo.historyPageFocusKey(towardTop: Boolean): StoredHistoryKey? {
    val candidates = visibleItemsInfo.filter { item ->
        item.key is StoredHistoryKey &&
            item.offset >= viewportStartOffset &&
            item.offset + item.size <= viewportEndOffset
    }
    val target = if (towardTop) {
        candidates.minByOrNull { item -> item.offset }
    } else {
        candidates.maxByOrNull { item -> item.offset + item.size }
    }
    return target?.key as? StoredHistoryKey
}

@Composable
internal fun StoredHistoryEntry(
    entry: AgentHistoryEntry,
    generation: Long,
    focusRequester: FocusRequester? = null,
    shellSessions: AgentShellSessionRegistry,
    onOpenContextMenu: ((
        generation: Long,
        storageIndex: Int,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)?,
) {
    val menuAnchor = rememberTuiPopupAnchor()
    TuiPressable(
        onClick = {},
        focusRequester = focusRequester,
        onSecondaryClick = onOpenContextMenu?.let { openMenu ->
            { clickPosition ->
                openMenu(
                    generation,
                    entry.key.primaryStorageIndex,
                    menuAnchor,
                    clickPosition,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(menuAnchor),
    ) { _, _, _ ->
        entry.event.render(shellSessions)
    }
}

@Composable
private fun HistoryMarkerText(value: String) {
    WrappedHistoryText(
        value = value,
        textStyle = TextStyle.Dim,
    )
}

/**
 * Mosaic's Text clips at its measured width instead of wrapping. Subcomposing
 * from the incoming finite width keeps each lazy item independently measurable.
 */
@Composable
internal fun WrappedHistoryText(
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
    color: Color = Color.Unspecified,
) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        check(constraints.hasBoundedWidth) {
            "Agent history text must be measured with a finite maximum width."
        }
        val wrapWidth = constraints.maxWidth.coerceAtLeast(1)
        val lines = value.wrapToTerminalWidth(wrapWidth)
        val placeable = subcompose(WrappedHistoryTextSlot) {
            Column {
                lines.forEach { line ->
                    Text(
                        value = line,
                        color = color,
                        textStyle = textStyle,
                    )
                }
            }
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = constraints.constrainHeight(placeable.height),
        ) {
            placeable.place(0, 0)
        }
    }
}

private fun StableCleanEvent.historyContentType(): HistoryContentType = when (this) {
    is StableCleanEvent.UserMessage,
    is StableCleanEvent.AssistantMessage,
    is StableCleanEvent.DeveloperMessage,
    is StableCleanEvent.AgentMessage,
        -> HistoryContentType.Message

    is StableCleanEvent.Reasoning -> HistoryContentType.Reasoning
    StableCleanEvent.ContextCompaction -> HistoryContentType.Context
    is StablePatchToolEvent -> HistoryContentType.Patch
    else -> HistoryContentType.CompletedTool
}

private fun UnstableCleanEvent.historyContentType(): HistoryContentType = when (this) {
    is PendingPatchToolEvent -> HistoryContentType.Patch
    else -> HistoryContentType.PendingTool
}

private fun UnstableCleanEvent.historyIdentity(position: Int): String = when (this) {
    is PendingToolEvent -> "call:$callId"
    is PendingServerToolSearch -> "server:${call.id?.value ?: position}"
}

private fun AgentStreamTail.historyIdentity(): Any = when (this) {
    AgentStreamTail.Started -> StreamingStartedHistoryKey
    is AgentStreamTail.Output -> events
    AgentStreamTail.Compacting -> CompactingHistoryKey
}

private fun AgentStreamTail.historyContentType(): HistoryContentType = when (this) {
    AgentStreamTail.Started,
    AgentStreamTail.Compacting,
        -> HistoryContentType.StreamingStatus

    is AgentStreamTail.Output -> when (kind) {
        io.github.stream29.kodex.app.agent.contract.AgentStreamKind.Message,
        io.github.stream29.kodex.app.agent.contract.AgentStreamKind.AgentMessage,
            -> HistoryContentType.StreamingMessage
        io.github.stream29.kodex.app.agent.contract.AgentStreamKind.Reasoning ->
            HistoryContentType.StreamingReasoning
        io.github.stream29.kodex.app.agent.contract.AgentStreamKind.ToolCall ->
            HistoryContentType.StreamingTool
        io.github.stream29.kodex.app.agent.contract.AgentStreamKind.Unknown ->
            HistoryContentType.StreamingStatus
    }
}

private fun AgentHistoryEdgeState.loadableCursor() = when (this) {
    is AgentHistoryEdgeState.Ready -> cursor
    is AgentHistoryEdgeState.Failed -> cursor
    AgentHistoryEdgeState.Exhausted,
    is AgentHistoryEdgeState.Loading,
    AgentHistoryEdgeState.Unresolved,
        -> null
}

internal data class StoredHistoryKey(
    val agentId: String,
    val generation: Long,
    val storageIndex: Int,
)

private data class PendingHistoryKey(
    val agentId: String,
    val generation: Long,
    val identity: String,
)

private data class StreamingHistoryKey(
    val agentId: String,
    val identity: Any,
)

private data class HistoryMarkerKey(
    val agentId: String,
    val generation: Long,
    val marker: HistoryMarker,
)

private enum class HistoryMarker {
    Loading,
    Failure,
    Empty,
}

private enum class HistoryContentType {
    StreamingStatus,
    StreamingMessage,
    StreamingReasoning,
    StreamingTool,
    Message,
    Reasoning,
    CompletedTool,
    PendingTool,
    Patch,
    Context,
    Marker,
}

private data object StreamingStartedHistoryKey
private data object CompactingHistoryKey

private data object WrappedHistoryTextSlot

private const val HistoryPrefetchDistance: Int = 4
private const val HistoryBatchSize: Int = 64
