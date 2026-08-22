package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
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
import kotlinx.coroutines.CancellationException
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
    val committedItemsState = model.committedItems.collectAsState()
    val committedItems = committedItemsState.value
    val generation = committedItems.generation
    val loadState by model.loadState.collectAsState()
    val pendingTools by model.pendingTools.collectAsState()
    val streamingItem by model.streamingItem.collectAsState()
    val historyTurnFooter by model.historyTurnFooter.collectAsState()
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
        historyTurnFooter?.let { footer ->
            item(
                key = HistoryTurnFooterKey(
                    markerIndex = footer.markerIndex,
                    endIndex = footer.endIndex,
                ),
                contentType = HistoryContentType.TurnFooter,
            ) {
                HistoryTurnFooterRow(footer.duration)
            }
        }

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
            count = committedItems.size,
            key = committedItems::peek,
            contentType = { position -> committedItems.peek(position).historyContentType() },
        ) { position ->
            val item = committedItems[position]
            if (item is HistoryItemViewModel.TurnFooter) {
                HistoryTurnFooterRow(item.duration)
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
                if (item is HistoryItemViewModel.WorkGroup) {
                    StoredHistoryWorkGroup(
                        group = item,
                        generation = generation,
                        model = model,
                        focusRequester = focusRequester,
                        shellSessions = shellSessions,
                        onOpenContextMenu = onOpenEntryContextMenu,
                    )
                } else {
                    StoredHistoryEntry(
                        item = item,
                        generation = generation,
                        model = model,
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
                    historyTurnFooter == null &&
                    committedItems.size == 0 &&
                    !state.hasOlder
                ) {
                    item(
                        key = HistoryMarkerKey(generation, HistoryMarker.Empty),
                        contentType = HistoryContentType.Marker,
                    ) {
                        HistoryMarkerText("No committed conversation items")
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryTurnFooterRow(duration: Duration) {
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
                val layoutInfo = androidx.compose.runtime.snapshotFlow { listState.layoutInfo }
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
            item.key !is HistoryItemViewModel.TurnFooter &&
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
    group: HistoryItemViewModel.WorkGroup,
    generation: Long,
    model: AgentHistoryViewModel,
    focusRequester: FocusRequester? = null,
    shellSessions: AgentShellSessionRegistry,
    onOpenContextMenu: ((
        generation: Long,
        storageIndex: Int,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)?,
) {
    val elapsed by produceState<Duration?>(
        initialValue = null,
        model,
        group,
        generation,
    ) {
        value = try {
            val result = model.elapsedSincePrevious(group)
            if (!model.contains(generation, group)) return@produceState
            result
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (model.contains(generation, group)) {
                HistoryViewLogger.error(failure) {
                    "Unable to read elapsed time for committed history group ${group.indexRange}."
                }
            }
            null
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = {
                group.toggleExpanded()
                model.notifyContentChanged()
            },
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth(),
        ) { _, isHovered, isPressed ->
            HistoryItemHeader(
                value = "${if (group.expanded) "v" else ">"} Take ${group.itemCount} actions",
                elapsed = elapsed,
                modifier = Modifier.fillMaxWidth(),
                textStyle = tuiInteractionTextStyle(
                    hovered = isHovered,
                    pressed = isPressed,
                ),
            )
        }
        if (group.expanded) {
            repeat(group.itemCount) { position ->
                StoredHistoryEntry(
                    item = group.childAt(position),
                    generation = generation,
                    model = model,
                    shellSessions = shellSessions,
                    onOpenContextMenu = onOpenContextMenu,
                )
            }
        }
    }
}

@Composable
internal fun StoredHistoryEntry(
    item: HistoryItemViewModel,
    generation: Long,
    model: AgentHistoryViewModel,
    focusRequester: FocusRequester? = null,
    shellSessions: AgentShellSessionRegistry,
    onOpenContextMenu: ((
        generation: Long,
        storageIndex: Int,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)?,
) {
    val storageIndex = item.storageIndex
    val menuAnchor = rememberTuiPopupAnchor()
    TuiPressable(
        onClick = {},
        focusRequester = focusRequester,
        onSecondaryClick = onOpenContextMenu?.let { openMenu ->
            { clickPosition ->
                openMenu(
                    generation,
                    storageIndex,
                    menuAnchor,
                    clickPosition,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(menuAnchor),
    ) { _, _, _ ->
        StoredHistoryContent(
            item = item,
            generation = generation,
            model = model,
            shellSessions = shellSessions,
        )
    }
}

@Composable
private fun StoredHistoryContent(
    item: HistoryItemViewModel,
    generation: Long,
    model: AgentHistoryViewModel,
    shellSessions: AgentShellSessionRegistry,
) {
    val loaded by produceState<StoredEventLoadState>(
        initialValue = StoredEventLoadState.Loading,
        model,
        item,
        generation,
    ) {
        value = try {
            val event = model.read(item)
            if (!model.contains(generation, item)) return@produceState
            val elapsed = try {
                model.elapsedSincePrevious(item)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (model.contains(generation, item)) {
                    HistoryViewLogger.error(failure) {
                        "Unable to read elapsed time for committed history item ${item.storageIndex}."
                    }
                }
                null
            }
            if (!model.contains(generation, item)) return@produceState
            StoredEventLoadState.Loaded(event, elapsed)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (!model.contains(generation, item)) return@produceState
            HistoryViewLogger.error(failure) {
                "Unable to read committed history item ${item.storageIndex}."
            }
            StoredEventLoadState.Failed
        }
    }

    when (val state = loaded) {
        StoredEventLoadState.Loading -> Text("")
        StoredEventLoadState.Failed -> Text(
            value = "Error",
            color = TuiTheme.colorScheme.error,
        )

        is StoredEventLoadState.Loaded -> {
            val expansion = remember(item) { item.expansionBinding() }
            state.event.render(
                shellSessions = shellSessions,
                expansion = expansion,
                elapsed = state.elapsed,
            )
        }
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
 * Mosaic's Text clips at its measured width instead of wrapping. Subcomposing from the incoming
 * finite width keeps each lazy item independently measurable.
 */
@Composable
internal fun WrappedHistoryText(
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
    color: Color = Color.Unspecified,
) {
    val layoutCache = remember(value) {
        WrappedHistoryTextLayoutCache(value)
    }
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        check(constraints.hasBoundedWidth) {
            "Agent history text must be measured with a finite maximum width."
        }
        val wrapWidth = constraints.maxWidth.coerceAtLeast(1)
        val lines = layoutCache.linesFor(wrapWidth)
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

private fun HistoryItemViewModel.historyContentType(): HistoryContentType = when (this) {
    is HistoryItemViewModel.Message -> HistoryContentType.Message
    is HistoryItemViewModel.Reasoning -> HistoryContentType.Reasoning
    is HistoryItemViewModel.Tool,
    is HistoryItemViewModel.RequestUserInput,
    is HistoryItemViewModel.PlanUpdate,
        -> HistoryContentType.CompletedTool

    is HistoryItemViewModel.Patch -> HistoryContentType.Patch
    is HistoryItemViewModel.ContextCompaction -> HistoryContentType.Context
    is HistoryItemViewModel.TurnFooter -> HistoryContentType.TurnFooter
    is HistoryItemViewModel.WorkGroup -> HistoryContentType.WorkGroup
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

private val HistoryItemViewModel.storageIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.Message -> index
        is HistoryItemViewModel.Reasoning -> index
        is HistoryItemViewModel.Tool -> index
        is HistoryItemViewModel.RequestUserInput -> index
        is HistoryItemViewModel.Patch -> index
        is HistoryItemViewModel.PlanUpdate -> index
        is HistoryItemViewModel.ContextCompaction -> index
        is HistoryItemViewModel.TurnFooter ->
            error("A turn footer cannot be rendered as a stored history event.")
        is HistoryItemViewModel.WorkGroup ->
            error("A folded history work group cannot be rendered as one stored event.")
    }

private fun HistoryItemViewModel.expansionBinding(): HistoryExpansionBinding? = when (this) {
    is HistoryItemViewModel.Reasoning -> HistoryExpansionBinding(
        expanded = { expanded },
        toggle = ::toggleExpanded,
    )

    is HistoryItemViewModel.Tool -> HistoryExpansionBinding(
        expanded = { expanded },
        toggle = ::toggleExpanded,
    )

    is HistoryItemViewModel.RequestUserInput -> HistoryExpansionBinding(
        expanded = { expanded },
        toggle = ::toggleExpanded,
    )

    is HistoryItemViewModel.Patch -> HistoryExpansionBinding(
        expanded = { expanded },
        toggle = ::toggleExpanded,
    )

    is HistoryItemViewModel.Message,
    is HistoryItemViewModel.PlanUpdate,
    is HistoryItemViewModel.ContextCompaction,
    is HistoryItemViewModel.TurnFooter,
    is HistoryItemViewModel.WorkGroup,
        -> null
}

private sealed interface StoredEventLoadState {
    data object Loading : StoredEventLoadState
    data object Failed : StoredEventLoadState
    data class Loaded(
        val event: StableCleanEvent,
        val elapsed: Duration?,
    ) : StoredEventLoadState
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
    TurnFooter,
    Marker,
}

private fun AgentHistoryViewModel.contains(
    generation: Long,
    item: HistoryItemViewModel,
): Boolean = when (item) {
    is HistoryItemViewModel.TurnFooter -> false
    is HistoryItemViewModel.WorkGroup ->
        contains(generation, item.indexRange.first) &&
            contains(generation, item.indexRange.last)

    else -> contains(generation, item.storageIndex)
}

private data object StreamingStartedHistoryKey
private data object CompactingHistoryKey
private data class HistoryTurnFooterKey(
    val markerIndex: Int,
    val endIndex: Int,
)
private data object WrappedHistoryTextSlot

private val HistoryViewLogger = KotlinLogging.logger {}
