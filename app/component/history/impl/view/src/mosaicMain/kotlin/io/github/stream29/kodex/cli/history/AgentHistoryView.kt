package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.ContextCompactionHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

/**
 * Renders the one real History owner for an Agent, not a projection into another page ViewModel.
 *
 * Rows borrow the bounded newest-first window and its exact item state machines. Viewport edges
 * request adjacent chunks; focus and popup anchors remain renderer-local. Loading, pending tools,
 * streaming and turn markers retain their own original presentation branches.
 *
 * [onOpenEntryContextMenu] receives the displayed generation, storage index and actual child,
 * including Work Group children. The host revalidates that captured target with [model]'s
 * `contains` before destructive actions; no resolver or synthetic parallel target is involved.
 * [shellSessions] supplies read-only process presentation to actual tool rows.
 *
 * Unmounting releases renderer focus bookkeeping only. The Agent binding, not this composition,
 * closes [model] and its item children.
 * [viewState] contains only borrowed renderer geometry/input. The host retains it by exact model
 * identity across tabs, not across binding replacement. No storage is read by this renderer.
 * Pending navigation is acknowledged only after measuring its exact destination; unmounting
 * beforehand leaves it pending. Framework input/render failures propagate to the renderer host.
 * @throws IllegalStateException if [viewState] is simultaneously attached to another list, or
 * the host does not provide a finite viewport height.
 */
@Composable
public fun AgentHistoryView(
    model: AgentHistoryViewModel,
    shellSessions: AgentShellSessionRegistry,
    viewState: AgentHistoryViewState = remember(model) { AgentHistoryViewState() },
    onOpenEntryContextMenu: ((
        generation: Long,
        storageIndex: Int,
        item: HistoryItemViewModel,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)? = null,
) {
    val historyItems = model.historyItems.collectAsState().value
    val generation = historyItems.generation
    val loadState by model.loadState.collectAsState()
    val pendingTools by model.pendingTools.collectAsState()
    val streamingItem by model.streamingItem.collectAsState()
    val followsLatest by model.followsLatest.collectAsState()
    val pendingScrollEffect by model.pendingScrollEffect.collectAsState()
    val listState = viewState.listState
    val interactionSource = viewState.scrollInteractionSource
    val transientPrefix = (if (streamingItem == null) 0 else 1) + pendingTools.size +
        (if ((historyItems.hasNewer && loadState == AgentHistoryLoadState.Ready) ||
            loadState == AgentHistoryLoadState.LoadingNewer
        ) 1 else 0)
    val entryFocusRequesters = remember(model) {
        mutableMapOf<HistoryItemViewModel, FocusRequester>()
    }

    HistoryPagingFocusEffect(
        listState = listState,
        interactionSource = interactionSource,
        entryFocusRequesters = entryFocusRequesters,
    )

    val displayedWindow = rememberUpdatedState(historyItems)
    DisposableEffect(model, viewState) {
        // Classify on the input emitter before its following measure pass can locally follow.
        // The listener lives for this mount, not this window: no no-replay subscription gap
        // when paging publishes another snapshot. The retained ViewState is unbound on disposal.
        val unbindInput = viewState.bindInput { interaction ->
            val window = displayedWindow.value
            if (interaction.orientation == ScrollOrientation.Vertical &&
                interaction.consumedDelta != 0 &&
                (interaction.source == ScrollInputSource.Pointer ||
                    interaction.source == ScrollInputSource.Keyboard)
            ) {
                if (interaction.consumedDelta < 0) {
                    model.setFollowsLatest(window, false)
                } else if (!listState.canScrollForward && !window.hasNewer) {
                    model.setFollowsLatest(window, true)
                }
            }
        }
        // Clear only at actual unmount, even if a newer snapshot was published just before
        // disposal. Ordinary window replacement must keep the last valid visible identities.
        onDispose {
            unbindInput()
            model.reportViewport(model.historyItems.value, emptyList())
        }
    }
    LaunchedEffect(model, viewState, historyItems) {
        snapshotFlow { listState.layoutInfo }.collect { layout ->
            model.reportViewport(historyItems, layout.visibleHistoryItems())
        }
    }
    LaunchedEffect(model, viewState, followsLatest, pendingScrollEffect) {
        // Layout/payload/stream height changes are local; they never generate VM navigation.
        snapshotFlow { listState.layoutInfo to listState.canScrollForward }.collect { (_, canScroll) ->
            if (model.followsLatest.value && model.pendingScrollEffect.value == null && canScroll) {
                listState.requestScrollToStart()
            }
        }
    }
    LaunchedEffect(model, viewState, historyItems, pendingScrollEffect, transientPrefix) {
        val effect = pendingScrollEffect ?: return@LaunchedEffect
        if (effect.generation != historyItems.generation ||
            model.pendingScrollEffect.value !== effect
        ) return@LaunchedEffect
        when (val target = effect.target) {
            HistoryScrollTarget.Latest -> {
                listState.requestScrollToStart()
                // Requests are consumed in layout, not by scrollToItem itself. Wait across a
                // complete renderer frame before accepting geometry from a previous provider.
                withFrameNanos {}
                withFrameNanos {}
                snapshotFlow { listState.layoutInfo to listState.canScrollForward }
                    .first { (layout, canScroll) -> layout.totalItemsCount > 0 && !canScroll }
            }
            is HistoryScrollTarget.Item -> {
                val position = (0 until historyItems.size).firstOrNull {
                    historyItems.peek(it) === target.item
                } ?: return@LaunchedEffect
                val rowIndex = transientPrefix + position
                listState.scrollToItem(rowIndex)
                withFrameNanos {}
                withFrameNanos {}
                snapshotFlow { listState.layoutInfo }.first { layout ->
                    layout.visibleItemsInfo.any { it.index == rowIndex && it.key === target.item }
                }
            }
        }
        if (model.historyItems.value === historyItems) model.acknowledgeScrollEffect(effect)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        interactionSource = interactionSource,
        keyboardPageSize = { viewportSize -> (viewportSize / 2).coerceAtLeast(1) },
    ) {
        streamingItem?.let { item ->
            item(
                key = item.historyIdentity(),
                contentType = item.historyContentType(),
            ) {
                item.renderTransientTail(onContentChange = {
                    if (model.followsLatest.value && model.pendingScrollEffect.value == null) {
                        listState.requestScrollToStart()
                    }
                })
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

        if (historyItems.hasNewer && loadState == AgentHistoryLoadState.Ready) {
            item(key = NewerHistoryDemandKey) {
                HistoryEdgeDemandEffect(
                    historyItems = historyItems,
                    model = model,
                    listState = listState,
                    edgePosition = 0,
                    request = historyItems::requestNewer,
                )
            }
        } else if (loadState is AgentHistoryLoadState.LoadingNewer) {
            item(key = NewerHistoryLoadingKey) {}
        }

        items(
            count = historyItems.size,
            key = historyItems::peek,
            contentType = { position -> historyItems.peek(position).historyContentType() },
        ) { position ->
            val item = historyItems[position]
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

        if (historyItems.hasOlder && loadState == AgentHistoryLoadState.Ready) {
            item(key = OlderHistoryDemandKey) {
                HistoryEdgeDemandEffect(
                    historyItems = historyItems,
                    model = model,
                    listState = listState,
                    edgePosition = historyItems.size - 1,
                    request = historyItems::requestOlder,
                )
            }
        }

        when (val state = loadState) {
            AgentHistoryLoadState.Initializing -> item(
                key = HistoryMarkerKey(generation, HistoryMarker.Loading),
                contentType = HistoryContentType.Marker,
            ) {
                HistoryMarkerText("Loading history…")
            }

            AgentHistoryLoadState.LoadingOlder -> item(key = OlderHistoryLoadingKey) {}

            AgentHistoryLoadState.LoadingNewer -> Unit

            is AgentHistoryLoadState.Failed -> item(
                key = HistoryMarkerKey(generation, HistoryMarker.Failure),
                contentType = HistoryContentType.Marker,
            ) {
                WrappedHistoryText(
                    value = "History error: ${state.message}",
                    color = TuiTheme.colorScheme.error,
                )
            }

            AgentHistoryLoadState.Ready -> {
                if (
                    streamingItem == null &&
                    pendingTools.isEmpty() &&
                    historyItems.size == 0 &&
                    !historyItems.hasOlder &&
                    !historyItems.hasNewer
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
private fun HistoryEdgeDemandEffect(
    historyItems: HistoryItemWindow,
    model: AgentHistoryViewModel,
    listState: LazyListState,
    edgePosition: Int,
    request: () -> Unit,
) {
    if (edgePosition !in 0 until historyItems.size) return
    val edgeItem = historyItems.peek(edgePosition)
    LaunchedEffect(historyItems, edgeItem, listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { item -> item.key === edgeItem }
        }.first { visible -> visible }
        model.reportViewport(historyItems, listState.layoutInfo.visibleHistoryItems())
        request()
    }
}

private fun LazyListLayoutInfo.visibleHistoryItems(): List<HistoryItemViewModel> =
    visibleItemsInfo.mapNotNull { it.key as? HistoryItemViewModel }

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
        item: HistoryItemViewModel,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)?,
) {
    val state by group.state.collectAsState()
    when (val currentState = state) {
        is WorkGroupHistoryItemState.Loading -> Text("")
        WorkGroupHistoryItemState.Failed -> HistoryErrorRow()
        is WorkGroupHistoryItemState.Collapsed,
        is WorkGroupHistoryItemState.Expanding,
        is WorkGroupHistoryItemState.Expanded -> {
            val expanded = currentState is WorkGroupHistoryItemState.Expanded
            val elapsed = when (currentState) {
                is WorkGroupHistoryItemState.Collapsed -> currentState.elapsed
                is WorkGroupHistoryItemState.Expanding -> currentState.elapsed
                is WorkGroupHistoryItemState.Expanded -> currentState.elapsed
            }
            Column(Modifier.fillMaxWidth()) {
                TuiPressable(
                    onClick = if (currentState is WorkGroupHistoryItemState.Collapsed) {
                        group::expand
                    } else {
                        group::collapse
                    },
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = focusRequester,
                ) { _, hovered, pressed ->
                    HistoryItemHeader(
                        value = "${if (expanded) "v" else ">"} Take ${group.itemCount} " +
                            if (group.itemCount == 1) "action" else "actions",
                        elapsed = elapsed,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = tuiInteractionTextStyle(hovered = hovered, pressed = pressed),
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
        item: HistoryItemViewModel,
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
                openMenu(generation, storageIndex, item, menuAnchor, clickPosition)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(menuAnchor),
    ) { _, _, _ ->
        StoredHistoryContent(
            item = item,
            shellSessions = shellSessions,
        )
    }
}

@Composable
private fun StoredHistoryContent(
    item: HistoryItemViewModel,
    shellSessions: AgentShellSessionRegistry,
) {
    when (item) {
        is ReasoningHistoryItemViewModel -> HistoryItemHeader(
            value = "Think",
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
            when (val current = state) {
                is MessageHistoryItemState.Loading -> Text("")
                MessageHistoryItemState.Failed -> HistoryErrorRow()
                is MessageHistoryItemState.Ready -> Column(Modifier.fillMaxWidth()) {
                    current.event.render(shellSessions, null, current.elapsed)
                    current.turnDuration?.let { HistoryTurnTimeMarkerRow(it) }
                }
            }
        }

        is RequestUserInputHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is RequestUserInputHistoryItemState.Loading -> Text("")
                RequestUserInputHistoryItemState.Failed -> HistoryErrorRow()
                is RequestUserInputHistoryItemState.Ready ->
                    current.event.render(shellSessions, null, current.elapsed)
            }
        }

        is SuggestSubagentTaskHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is SuggestSubagentTaskHistoryItemState.Loading -> Text("")
                SuggestSubagentTaskHistoryItemState.Failed -> HistoryErrorRow()
                is SuggestSubagentTaskHistoryItemState.Ready ->
                    current.event.renderSuggestion(current.elapsed)
            }
        }

        is PlanUpdateHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val current = state) {
                is PlanUpdateHistoryItemState.Loading -> Text("")
                PlanUpdateHistoryItemState.Failed -> HistoryErrorRow()
                is PlanUpdateHistoryItemState.Ready ->
                    current.event.render(shellSessions, null, current.elapsed)
            }
        }

        is ToolHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is ToolHistoryItemState.Loading -> Text("")
                ToolHistoryItemState.Failed -> HistoryErrorRow()
                is ToolHistoryItemState.Collapsed ->
                    CollapsedToolHistoryRow(currentState.header, shellSessions, item::expand)
                is ToolHistoryItemState.Expanding ->
                    CollapsedToolHistoryRow(currentState.header, shellSessions, item::collapse)
                is ToolHistoryItemState.Expanded -> currentState.event.render(
                    shellSessions, HistoryExpansionBinding({ true }, item::collapse),
                    currentState.header.elapsed,
                )
            }
        }

        is PatchHistoryItemViewModel -> {
            val state by item.state.collectAsState()
            when (val currentState = state) {
                is PatchHistoryItemState.Loading -> Text("")
                PatchHistoryItemState.Failed -> HistoryErrorRow()
                is PatchHistoryItemState.Collapsed -> CollapsedToolHistoryRow(
                    currentState.header.summary(), currentState.header.status.historyStatus(),
                    currentState.header.elapsed, item::expand,
                )
                is PatchHistoryItemState.Expanding -> CollapsedToolHistoryRow(
                    currentState.header.summary(), currentState.header.status.historyStatus(),
                    currentState.header.elapsed, item::collapse,
                )
                is PatchHistoryItemState.Expanded -> currentState.event.render(
                    shellSessions, HistoryExpansionBinding({ true }, item::collapse),
                    currentState.header.elapsed,
                )
            }
        }

        is WorkGroupHistoryItemViewModel,
            -> error("Virtual history rows are rendered by their owning branch.")
    }
}

@Composable
private fun HistoryErrorRow() {
    Text(
        value = "Error",
        color = TuiTheme.colorScheme.error,
    )
}

@Composable
private fun CollapsedToolHistoryRow(
    header: ToolHistoryItemHeader,
    shellSessions: AgentShellSessionRegistry,
    onClick: () -> Unit,
) {
    when (header) {
        is ToolHistoryItemHeader.Summary -> CollapsedToolHistoryRow(
            summary = header.summary,
            status = header.status,
            elapsed = header.elapsed,
            onClick = onClick,
        )

        is ToolHistoryItemHeader.CommandExecution -> {
            val session = header.activeSession(shellSessions)
            val processCompleted = session.completedForHistoryPresentation()
            CollapsedToolHistoryRow(
                summary = header.summary(session),
                status = header.status(session, processCompleted),
                elapsed = header.elapsed,
                onClick = onClick,
            )
        }
    }
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

@Composable
private fun ToolHistoryItemHeader.CommandExecution.activeSession(
    registry: AgentShellSessionRegistry,
): AgentShellSession? {
    val sessionId = when (val action = action) {
        is CommandExecutionHistoryAction.Run ->
            (result as? CommandExecutionHistoryResult.Output)?.sessionId

        is CommandExecutionHistoryAction.Interact -> action.sessionId
        is CommandExecutionHistoryAction.Wait -> action.sessionId
    } ?: return null
    val sessions by registry.activeSessions.collectAsState()
    return sessions[sessionId]
}

@Composable
private fun AgentShellSession?.completedForHistoryPresentation(): Boolean {
    if (this == null) return false
    val completed by completed.collectAsState()
    return completed
}

private fun ToolHistoryItemHeader.CommandExecution.summary(
    session: AgentShellSession?,
): String = when (val action = action) {
    is CommandExecutionHistoryAction.Run ->
        action.command.takeIf(String::isNotBlank)?.let { command ->
            if (result == CommandExecutionHistoryResult.Failure) "Failed to run: $command" else "Run: $command"
        } ?: if (result == CommandExecutionHistoryResult.Failure) "Failed to run" else "Run"

    is CommandExecutionHistoryAction.Interact ->
        session?.arguments?.command?.historyCommandPreview()
            ?.takeIf(String::isNotBlank)
            ?.let { command ->
                if (result == CommandExecutionHistoryResult.Failure) {
                    "Failed to interact with $command"
                } else {
                    "Interact with $command"
                }
            }
            ?: if (result == CommandExecutionHistoryResult.Failure) {
                "Failed to interact with terminal session ${action.sessionId}"
            } else {
                "Interact with terminal session ${action.sessionId}"
            }

    is CommandExecutionHistoryAction.Wait ->
        session?.arguments?.command?.historyCommandPreview()
            ?.takeIf(String::isNotBlank)
            ?.let { command ->
                if (result == CommandExecutionHistoryResult.Failure) {
                    "Failed to wait for $command"
                } else {
                    "Wait for $command"
                }
            }
            ?: if (result == CommandExecutionHistoryResult.Failure) {
                "Failed to wait for terminal session ${action.sessionId}"
            } else {
                "Wait for terminal session ${action.sessionId}"
            }
}

private fun ToolHistoryItemHeader.CommandExecution.status(
    session: AgentShellSession?,
    processCompleted: Boolean,
): String = when (val result = result) {
    CommandExecutionHistoryResult.Failure -> "failed"
    is CommandExecutionHistoryResult.Output -> when (result.exitCode) {
        0 -> "succeeded"
        null -> if (session == null || processCompleted) "finished" else "running"
        else -> "failed"
    }
}

private fun io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemHeader.summary(): String {
    val verb = when (status) {
        PatchHistoryItemStatus.Completed -> "Edit"
        PatchHistoryItemStatus.Failed -> "Failed to edit"
    }
    return when (val target = target) {
        is PatchHistoryItemTarget.SingleFile -> "$verb ${target.filename}"
        is PatchHistoryItemTarget.FileCount ->
            "$verb ${target.count} ${if (target.count == 1) "file" else "files"}"
    }
}

private fun PatchHistoryItemStatus.historyStatus(): String = when (this) {
    PatchHistoryItemStatus.Completed -> "completed"
    PatchHistoryItemStatus.Failed -> "failed"
}

private fun String.historyCommandPreview(): String {
    val singleLine = lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
    return if (singleLine.length <= MaximumHistoryCommandPreviewLength) {
        singleLine
    } else {
        singleLine.take(MaximumHistoryCommandPreviewLength - 3).trimEnd() + "..."
    }
}

private fun HistoryItemViewModel.storageIndex(): Int = when (this) {
    is MessageHistoryItemViewModel -> index
    is ReasoningHistoryItemViewModel -> index
    is ToolHistoryItemViewModel -> index
    is RequestUserInputHistoryItemViewModel -> index
    is SuggestSubagentTaskHistoryItemViewModel -> index
    is PatchHistoryItemViewModel -> index
    is PlanUpdateHistoryItemViewModel -> index
    is ContextCompactionHistoryItemViewModel -> index
    is WorkGroupHistoryItemViewModel,
        -> error("A virtual history item has no context-menu storage index.")
}

private fun HistoryItemViewModel.historyContentType(): HistoryContentType = when (this) {
    is MessageHistoryItemViewModel -> HistoryContentType.Message
    is ReasoningHistoryItemViewModel -> HistoryContentType.Reasoning
    is ToolHistoryItemViewModel,
    is RequestUserInputHistoryItemViewModel,
    is SuggestSubagentTaskHistoryItemViewModel,
    is PlanUpdateHistoryItemViewModel,
        -> HistoryContentType.CompletedTool

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
    HistoryStreamingItem.Started,
    HistoryStreamingItem.Compacting,
        -> HistoryContentType.StreamingStatus

    is HistoryStreamingItem.Output -> when (kind) {
        HistoryStreamingKind.Message,
        HistoryStreamingKind.AgentMessage,
            -> HistoryContentType.StreamingMessage

        HistoryStreamingKind.Reasoning -> HistoryContentType.StreamingReasoning
        HistoryStreamingKind.ToolCall -> HistoryContentType.StreamingTool
        HistoryStreamingKind.Unknown -> HistoryContentType.StreamingTool
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

private data object OlderHistoryDemandKey

private data object OlderHistoryLoadingKey

private data object NewerHistoryDemandKey

private data object NewerHistoryLoadingKey

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
    Marker,
}

private data object StreamingStartedHistoryKey
private data object CompactingHistoryKey

private const val MaximumHistoryCommandPreviewLength: Int = 240

@Composable
private fun HistoryMarkerText(value: String) {
    WrappedHistoryText(
        value = value,
        textStyle = TextStyle.Dim,
    )
}
