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
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** Renders one Agent history model. */
@Composable
public fun AgentHistoryView(
    model: AgentHistoryViewModel,
    unifiedExecToolClient: UnifiedExecToolClient? = null,
    onOpenEntryContextMenu: ((
        generation: Long,
        storageIndex: Int,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)? = null,
) {
    val agentId = model.agentState.storage.id
    val listState = remember(agentId) { LazyListState() }
    val scrollInteractionSource = remember(agentId) { MutableScrollInteractionSource() }
    val entryFocusRequesters = remember(agentId) {
        mutableMapOf<StoredHistoryKey, FocusRequester>()
    }
    var window by remember(model, agentId) { mutableStateOf(model.window.value) }
    var requestResponse by remember(model, agentId) { mutableStateOf(model.requestResponse.value) }
    val streamingTailCount = if (requestResponse == null) 0 else 1
    HistoryPagingFocusEffect(
        listState = listState,
        interactionSource = scrollInteractionSource,
        entryFocusRequesters = entryFocusRequesters,
    )

    LaunchedEffect(model, agentId, listState) {
        model.window.collect { updatedWindow ->
            if (updatedWindow != window) {
                listState.requestLatestIfAtLatest()
                window = updatedWindow
            }
        }
    }
    LaunchedEffect(model, agentId, listState) {
        model.requestResponse.collect { updatedRequestResponse ->
            if (updatedRequestResponse != requestResponse) {
                listState.requestLatestIfAtLatest()
                requestResponse = updatedRequestResponse
            }
        }
    }

    LaunchedEffect(
        model,
        agentId,
        listState,
        window.generation,
        streamingTailCount,
        window.pending.size,
        window.entries.size,
        window.hasOlderEntries,
        window.isLoading,
    ) {
        if (
            window.entries.isEmpty() ||
            !window.hasOlderEntries ||
            window.isLoading
        ) {
            return@LaunchedEffect
        }

        val loadBoundary = (
            streamingTailCount + window.pending.size + window.entries.lastIndex - HistoryPrefetchDistance
            ).coerceAtLeast(0)
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { item ->
                item.index >= loadBoundary
            }
        }
            .distinctUntilChanged()
            .filter { nearOlderBoundary -> nearOlderBoundary }
            .collect { model.loadOlder() }
    }

    key(agentId) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            interactionSource = scrollInteractionSource,
            keyboardPageSize = { viewportSize -> (viewportSize / 2).coerceAtLeast(1) },
        ) {
            requestResponse?.let { request ->
                item(
                    key = StreamingHistoryKey(
                        agentId = agentId,
                        identity = request.historyIdentity(),
                    ),
                    contentType = request.historyContentType(),
                ) {
                    request.renderStreamingTail(
                        onContentChange = listState::requestLatestIfAtLatest,
                    )
                }
            }

            val pending = window.pending.asReversed()
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
                pending[position].render(unifiedExecToolClient)
            }

            items(
                items = window.entries,
                key = { entry ->
                    StoredHistoryKey(
                        agentId = agentId,
                        generation = window.generation,
                        storageIndex = entry.index,
                    )
                },
                contentType = { entry -> entry.event.historyContentType() },
            ) { entry ->
                val entryKey = StoredHistoryKey(
                    agentId = agentId,
                    generation = window.generation,
                    storageIndex = entry.index,
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
                    unifiedExecToolClient = unifiedExecToolClient,
                    onOpenContextMenu = onOpenEntryContextMenu,
                )
            }

            if (window.isLoading) {
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

            window.failureMessage?.let { failureMessage ->
                item(
                    key = HistoryMarkerKey(
                        agentId = agentId,
                        generation = window.generation,
                        marker = HistoryMarker.Failure,
                    ),
                    contentType = HistoryContentType.Marker,
                ) {
                    HistoryMarkerText("History error: $failureMessage")
                }
            }

            if (
                requestResponse == null &&
                window.entries.isEmpty() &&
                window.pending.isEmpty() &&
                !window.isLoading &&
                window.failureMessage == null
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

/**
 * Requests the visual latest edge only when this reverse-layout history was already there.
 *
 * Call this before changing the items supplied to the list. In a reverse layout, the visual
 * latest edge is the logical start and has no remaining forward scroll range.
 */
internal fun LazyListState.requestLatestIfAtLatest() {
    if (!canScrollForward) requestScrollToStart()
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
    entry: AgentHistoryStoredEntry,
    generation: Long,
    focusRequester: FocusRequester? = null,
    unifiedExecToolClient: UnifiedExecToolClient?,
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
                    entry.index,
                    menuAnchor,
                    clickPosition,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(menuAnchor),
    ) { _, _, _ ->
        entry.event.render(unifiedExecToolClient)
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

private fun KodexAgentStateValue.RequestResponse.historyIdentity(): Any = when (this) {
    KodexAgentStateValue.RequestResponse.Started -> StreamingStartedHistoryKey
    is KodexAgentStateValue.RequestResponse.Message -> events
    is KodexAgentStateValue.RequestResponse.AgentMessage -> events
    is KodexAgentStateValue.RequestResponse.Reasoning -> events
    is KodexAgentStateValue.RequestResponse.ToolCall -> events
    is KodexAgentStateValue.RequestResponse.Unknown -> events
}

private fun KodexAgentStateValue.RequestResponse.historyContentType(): HistoryContentType = when (this) {
    KodexAgentStateValue.RequestResponse.Started,
    is KodexAgentStateValue.RequestResponse.Unknown,
        -> HistoryContentType.StreamingStatus

    is KodexAgentStateValue.RequestResponse.Message,
    is KodexAgentStateValue.RequestResponse.AgentMessage,
        -> HistoryContentType.StreamingMessage

    is KodexAgentStateValue.RequestResponse.Reasoning -> HistoryContentType.StreamingReasoning
    is KodexAgentStateValue.RequestResponse.ToolCall -> HistoryContentType.StreamingTool
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

private data object WrappedHistoryTextSlot

private const val HistoryPrefetchDistance: Int = 4
