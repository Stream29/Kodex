package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.history.contract.item.ContextCompactionHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.TurnTimeMarkerHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListLayoutInfo
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

/** Renders one Agent's complete History ViewModel. */
@Composable
public fun AgentHistoryView(
    model: AgentHistoryViewModel,
    shellSessions: AgentShellSessionRegistry,
    onOpenEntryContextMenu: ((
        generation: Long,
        storageIndex: Int,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)? = null,
) {
    val historyItems = model.historyItems.collectAsState().value
    val generation = historyItems.generation
    val loadState by model.loadState.collectAsState()
    val pendingTools by model.pendingTools.collectAsState()
    val streamingItem by model.streamingItem.collectAsState()
    val entryFocusRequesters = remember(model) {
        mutableMapOf<HistoryItemViewModel, FocusRequester>()
    }

    HistoryPagingFocusEffect(
        listState = model.listState,
        interactionSource = model.scrollInteractionSource,
        entryFocusRequesters = entryFocusRequesters,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = model.listState,
        reverseLayout = true,
        interactionSource = model.scrollInteractionSource,
        keyboardPageSize = { viewportSize -> (viewportSize / 2).coerceAtLeast(1) },
    ) {
        streamingItem?.let { item ->
            item(
                key = item.historyIdentity(),
                contentType = item.historyContentType(),
            ) {
                item.renderTransientTail(onContentChange = model::notifyContentChanged)
            }
        }

        val pending = pendingTools.asReversed()
        items(
            count = pending.size,
            key = { position ->
                PendingHistoryKey(
                    generation = generation,
                    identity = pending[position].historyIdentity(position),
                )
            },
            contentType = { position -> pending[position].historyContentType() },
        ) { position ->
            pending[position].render(shellSessions)
        }

        items(
            count = historyItems.size,
            key = historyItems::peek,
            contentType = { position -> historyItems.peek(position).historyContentType() },
        ) { position ->
            val item = historyItems[position]
            if (item is TurnTimeMarkerHistoryItemViewModel) {
                HistoryTurnTimeMarkerRow(item.duration)
            } else {
                val focusRequester = remember(item) { FocusRequester() }
                DisposableEffect(item, focusRequester) {
                    entryFocusRequesters[item] = focusRequester
                    onDispose {
                        if (entryFocusRequesters[item] === focusRequester) {
                            entryFocusRequesters.remove(item)
                        }
                    }
                }
                if (item is WorkGroupHistoryItemViewModel) {
                    StoredHistoryWorkGroup(
                        group = item,
                        generation = generation,
                        focusRequester = focusRequester,
                        shellSessions = shellSessions,
                        onOpenContextMenu = onOpenEntryContextMenu,
                    )
                } else {
                    StoredHistoryEntry(
                        item = item,
                        generation = generation,
                        focusRequester = focusRequester,
                        shellSessions = shellSessions,
                        onOpenContextMenu = onOpenEntryContextMenu,
                    )
                }
            }
        }

        when (val state = loadState) {
            AgentHistoryLoadState.Initializing,
            AgentHistoryLoadState.LoadingOlder,
                -> item(
                key = HistoryMarkerKey(generation, HistoryMarker.Loading),
                contentType = HistoryContentType.Marker,
            ) {
                HistoryMarkerText("Loading history…")
            }

            is AgentHistoryLoadState.Failed -> item(
                key = HistoryMarkerKey(generation, HistoryMarker.Failure),
                contentType = HistoryContentType.Marker,
            ) {
                WrappedHistoryText(
                    value = "History error: ${state.message}",
                    color = TuiTheme.colorScheme.error,
                )
            }

            is AgentHistoryLoadState.Ready -> {
                if (
                    streamingItem == null &&
                    pendingTools.isEmpty() &&
                    historyItems.size == 0 &&
                    !state.hasOlder
                ) {
                    item(
                        key = HistoryMarkerKey(generation, HistoryMarker.Empty),
                        contentType = HistoryContentType.Marker,
                    ) {
                        HistoryMarkerText("No conversation history items")
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryTurnTimeMarkerRow(duration: Duration) {
    Text(
        value = "---Worked for ${duration.roundToMilliseconds()}---",
        modifier = Modifier.fillMaxWidth(),
        color = Color.Unspecified,
        textStyle = TextStyle.Dim,
    )
}

@Composable
internal fun HistoryPagingFocusEffect(
    listState: LazyListState,
    interactionSource: MutableScrollInteractionSource,
    entryFocusRequesters: Map<HistoryItemViewModel, FocusRequester>,
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
                val targetItem = layoutInfo.historyPageFocusItem(
                    towardTop = interaction.consumedDelta < 0,
                ) ?: return@collectLatest
                entryFocusRequesters[targetItem]?.requestFocus()
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

internal fun LazyListLayoutInfo.historyPageFocusItem(
    towardTop: Boolean,
): HistoryItemViewModel? {
    val candidates = visibleItemsInfo.filter { item ->
        item.key is HistoryItemViewModel &&
            item.key !is TurnTimeMarkerHistoryItemViewModel &&
            item.key !is WorkGroupHistoryItemViewModel &&
            item.offset >= viewportStartOffset &&
            item.offset + item.size <= viewportEndOffset
    }
    val target = if (towardTop) {
        candidates.minByOrNull { item -> item.offset }
    } else {
        candidates.maxByOrNull { item -> item.offset + item.size }
    }
    return target?.key as? HistoryItemViewModel
}

@Composable
internal fun StoredHistoryWorkGroup(
    group: WorkGroupHistoryItemViewModel,
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
    val state by group.state.collectAsState()
    Column(modifier = Modifier.fillMaxWidth()) {
        when (val currentState = state) {
            is WorkGroupHistoryItemState.Loading -> Text("")
            WorkGroupHistoryItemState.Failed -> HistoryErrorRow()
            is WorkGroupHistoryItemState.Collapsed,
            is WorkGroupHistoryItemState.Expanding,
            is WorkGroupHistoryItemState.Expanded,
                -> {
                val expanded = currentState is WorkGroupHistoryItemState.Expanded
                val elapsed = when (currentState) {
                    is WorkGroupHistoryItemState.Collapsed -> currentState.elapsed
                    is WorkGroupHistoryItemState.Expanding -> currentState.elapsed
                    is WorkGroupHistoryItemState.Expanded -> currentState.elapsed
                }
                TuiPressable(
                    onClick = {
                        when (currentState) {
                            is WorkGroupHistoryItemState.Collapsed -> group.expand()
                            is WorkGroupHistoryItemState.Expanding,
                            is WorkGroupHistoryItemState.Expanded,
                                -> group.collapse()
                        }
                    },
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth(),
                ) { _, isHovered, isPressed ->
                    HistoryItemHeader(
                        value = "${if (expanded) "v" else ">"} Take ${group.itemCount} actions",
                        elapsed = elapsed,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = tuiInteractionTextStyle(
                            hovered = isHovered,
                            pressed = isPressed,
                        ),
                    )
                }
                if (currentState is WorkGroupHistoryItemState.Expanded) {
                    currentState.children.forEach { child ->
                        StoredHistoryEntry(
                            item = child,
                            generation = generation,
                            shellSessions = shellSessions,
                            onOpenContextMenu = onOpenContextMenu,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StoredHistoryEntry(
    item: HistoryItemViewModel,
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
    val storageIndex = item.storageIndex()
    val menuAnchor = rememberTuiPopupAnchor()
    TuiPressable(
        onClick = {},
        focusRequester = focusRequester,
        onSecondaryClick = onOpenContextMenu?.let { openMenu ->
            { clickPosition ->
                openMenu(generation, storageIndex, menuAnchor, clickPosition)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(menuAnchor),
    ) { _, _, _ ->
        StoredHistoryContent(item = item, shellSessions = shellSessions)
    }
}

@Composable
private fun StoredHistoryContent(
    item: HistoryItemViewModel,
    shellSessions: AgentShellSessionRegistry,
) {
    when (item) {
        is ReasoningHistoryItemViewModel -> HistoryItemHeader(
            value = "Thinking",
            elapsed = item.elapsed,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle.Dim,
        )

        is ContextCompactionHistoryItemViewModel -> HistoryItemHeader(
            value = "Context compacted",
            elapsed = item.elapsed,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle.Dim,
        )

        is MessageHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is MessageHistoryItemState.Loading -> Text("")
                MessageHistoryItemState.Failed -> HistoryErrorRow()
                is MessageHistoryItemState.Ready -> currentState.event.render(
                    shellSessions = shellSessions,
                    expansion = null,
                    elapsed = currentState.elapsed,
                )
            }
        }

        is RequestUserInputHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is RequestUserInputHistoryItemState.Loading -> Text("")
                RequestUserInputHistoryItemState.Failed -> HistoryErrorRow()
                is RequestUserInputHistoryItemState.Ready -> currentState.event.render(
                    shellSessions = shellSessions,
                    expansion = AlwaysExpandedHistoryBinding,
                    elapsed = currentState.elapsed,
                )
            }
        }

        is PlanUpdateHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is PlanUpdateHistoryItemState.Loading -> Text("")
                PlanUpdateHistoryItemState.Failed -> HistoryErrorRow()
                is PlanUpdateHistoryItemState.Ready -> currentState.event.render(
                    shellSessions = shellSessions,
                    expansion = AlwaysExpandedHistoryBinding,
                    elapsed = currentState.elapsed,
                )
            }
        }

        is ToolHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is ToolHistoryItemState.Loading -> Text("")
                ToolHistoryItemState.Failed -> HistoryErrorRow()
                is ToolHistoryItemState.Collapsed -> CollapsedToolHistoryRow(
                    summary = currentState.header.summary,
                    status = currentState.header.status,
                    elapsed = currentState.header.elapsed,
                    onClick = item::expand,
                )

                is ToolHistoryItemState.Expanding -> CollapsedToolHistoryRow(
                    summary = currentState.header.summary,
                    status = currentState.header.status,
                    elapsed = currentState.header.elapsed,
                    onClick = item::collapse,
                )

                is ToolHistoryItemState.Expanded -> currentState.event.render(
                    shellSessions = shellSessions,
                    expansion = HistoryExpansionBinding(
                        expanded = { true },
                        toggle = item::collapse,
                    ),
                    elapsed = currentState.header.elapsed,
                )
            }
        }

        is PatchHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is PatchHistoryItemState.Loading -> Text("")
                PatchHistoryItemState.Failed -> HistoryErrorRow()
                is PatchHistoryItemState.Collapsed -> CollapsedToolHistoryRow(
                    summary = currentState.header.summary,
                    status = currentState.header.status.name.lowercase(),
                    elapsed = currentState.header.elapsed,
                    onClick = item::expand,
                )

                is PatchHistoryItemState.Expanding -> CollapsedToolHistoryRow(
                    summary = currentState.header.summary,
                    status = currentState.header.status.name.lowercase(),
                    elapsed = currentState.header.elapsed,
                    onClick = item::collapse,
                )

                is PatchHistoryItemState.Expanded -> currentState.event.render(
                    shellSessions = shellSessions,
                    expansion = HistoryExpansionBinding(
                        expanded = { true },
                        toggle = item::collapse,
                    ),
                    elapsed = currentState.header.elapsed,
                )
            }
        }

        is TurnTimeMarkerHistoryItemViewModel,
        is WorkGroupHistoryItemViewModel,
            -> error("Virtual history rows are rendered by their owning branch.")
    }
}

private val AlwaysExpandedHistoryBinding = HistoryExpansionBinding(
    expanded = { true },
    toggle = {},
)

@Composable
private fun HistoryErrorRow() {
    Text(
        value = "Error",
        color = TuiTheme.colorScheme.error,
    )
}

@Composable
private fun CollapsedToolHistoryRow(
    summary: String,
    status: String,
    elapsed: Duration,
    onClick: () -> Unit,
) {
    TuiPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) { _, isHovered, isPressed ->
        HistoryItemHeader(
            value = "> $summary",
            elapsed = elapsed,
            modifier = Modifier.fillMaxWidth(),
            color = when (status) {
                "failed" -> TuiTheme.colorScheme.error
                "running", "streaming", "starting", "in_progress", "inprogress" ->
                    TuiTheme.colorScheme.success

                else -> Color.Unspecified
            },
            textStyle = tuiInteractionTextStyle(
                hovered = isHovered,
                pressed = isPressed,
            ),
        )
    }
}

private fun HistoryItemViewModel.storageIndex(): Int = when (this) {
    is MessageHistoryItemViewModel -> index
    is ReasoningHistoryItemViewModel -> index
    is ToolHistoryItemViewModel -> index
    is RequestUserInputHistoryItemViewModel -> index
    is PatchHistoryItemViewModel -> index
    is PlanUpdateHistoryItemViewModel -> index
    is ContextCompactionHistoryItemViewModel -> index
    is TurnTimeMarkerHistoryItemViewModel,
    is WorkGroupHistoryItemViewModel,
        -> error("A virtual history item has no context-menu storage index.")
}

private fun HistoryItemViewModel.historyContentType(): HistoryContentType = when (this) {
    is MessageHistoryItemViewModel -> HistoryContentType.Message
    is ReasoningHistoryItemViewModel -> HistoryContentType.Reasoning
    is ToolHistoryItemViewModel,
    is RequestUserInputHistoryItemViewModel,
    is PlanUpdateHistoryItemViewModel,
        -> HistoryContentType.CompletedTool

    is PatchHistoryItemViewModel -> HistoryContentType.Patch
    is ContextCompactionHistoryItemViewModel -> HistoryContentType.Context
    is TurnTimeMarkerHistoryItemViewModel -> HistoryContentType.TurnTimeMarker
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
    HistoryStreamingItem.Started,
    HistoryStreamingItem.Compacting,
        -> HistoryContentType.StreamingStatus

    is HistoryStreamingItem.Output -> when (kind) {
        HistoryStreamingKind.Message,
        HistoryStreamingKind.AgentMessage,
            -> HistoryContentType.StreamingMessage

        HistoryStreamingKind.Reasoning -> HistoryContentType.StreamingReasoning
        HistoryStreamingKind.ToolCall -> HistoryContentType.StreamingTool
        HistoryStreamingKind.Unknown -> HistoryContentType.StreamingStatus
    }
}

private data class PendingHistoryKey(
    val generation: Long,
    val identity: String,
)

private data class HistoryMarkerKey(
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
    WorkGroup,
    TurnTimeMarker,
    Marker,
}

private data object StreamingStartedHistoryKey
private data object CompactingHistoryKey
private data object WrappedHistoryTextSlot

@Composable
private fun HistoryMarkerText(value: String) {
    WrappedHistoryText(
        value = value,
        textStyle = TextStyle.Dim,
    )
}

/**
 * Mosaic's Text clips at its measured width instead of wrapping. Subcomposing from the incoming
 * finite width keeps each lazy item independently measurable.
 */
@Composable
internal fun WrappedHistoryText(
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
    color: Color = Color.Unspecified,
) {
    val layoutCache = remember(value) { WrappedHistoryTextLayoutCache(value) }
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        check(constraints.hasBoundedWidth) {
            "Agent history text must be measured with a finite maximum width."
        }
        val wrapWidth = constraints.maxWidth.coerceAtLeast(1)
        val lines = layoutCache.linesFor(wrapWidth)
        val placeable = subcompose(WrappedHistoryTextSlot) {
            Column {
                lines.forEach { line ->
                    Text(value = line, color = color, textStyle = textStyle)
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

internal class WrappedHistoryTextLayoutCache(
    private val value: String,
) {
    private var cachedWidth: Int? = null
    private var cachedLines: List<String> = emptyList()

    internal fun linesFor(width: Int): List<String> {
        if (cachedWidth != width) {
            cachedWidth = width
            cachedLines = value.wrapToTerminalWidth(width)
        }
        return cachedLines
    }
}
