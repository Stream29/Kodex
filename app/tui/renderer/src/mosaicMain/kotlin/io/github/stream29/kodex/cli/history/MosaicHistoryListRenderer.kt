package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryScrollEffect
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
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
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

internal class MosaicHistoryListRenderer(
    private val viewState: AgentHistoryViewState,
    private val onOpenEntryContextMenu: ((
        generation: Long,
        storageIndex: Int,
        item: HistoryItemViewModel,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)?,
) : HistoryListRenderer, HistoryContentRenderer by MosaicHistoryContentRenderer() {
    @Composable override fun List(
        model: AgentHistoryViewModel, window: HistoryItemWindow, followsLatest: Boolean,
        effect: HistoryScrollEffect?, firstCommittedIndex: Int, latestKey: Any?,
        content: (HistoryListScope) -> Unit,
    ) {
        val historyItems = window
        val pendingScrollEffect = effect
        val listState = viewState.listState
        val interactionSource = viewState.scrollInteractionSource
        val transientPrefix = firstCommittedIndex
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
            // Keep this listener across window replacement; unbind only the actual surface.
            val unbindInput = viewState.bindInput { interaction ->
                val current = displayedWindow.value
                if (interaction.orientation == ScrollOrientation.Vertical &&
                    interaction.consumedDelta != 0 &&
                    (interaction.source == ScrollInputSource.Pointer || interaction.source == ScrollInputSource.Keyboard)
                ) {
                    if (interaction.consumedDelta < 0) {
                        model.setFollowsLatest(current, false)
                    } else if (!listState.canScrollForward && !current.hasNewer) {
                        model.setFollowsLatest(current, true)
                    }
                }
            }
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
            // Layout/payload/stream height changes are local, not VM navigation requests.
            snapshotFlow { listState.layoutInfo to listState.canScrollForward }.collect { (_, canScroll) ->
                if (model.followsLatest.value && model.pendingScrollEffect.value == null && canScroll) {
                    listState.requestScrollToStart()
                }
            }
        }
        LaunchedEffect(model, viewState, historyItems, pendingScrollEffect, transientPrefix) {
            val expected = pendingScrollEffect ?: return@LaunchedEffect
            if (expected.generation != historyItems.generation || model.pendingScrollEffect.value !== expected) {
                return@LaunchedEffect
            }
            when (val target = expected.target) {
                HistoryScrollTarget.Latest -> {
                    listState.requestScrollToStart()
                    // Wait for layout to consume the request before inspecting provider geometry.
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
            if (model.historyItems.value === historyItems) model.acknowledgeScrollEffect(expected)
        }

        CompositionLocalProvider(LocalHistoryEntryFocus provides entryFocusRequesters) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                interactionSource = interactionSource,
                keyboardPageSize = { viewportSize -> (viewportSize / 2).coerceAtLeast(1) },
            ) {
                content(MosaicHistoryListScope(this))
            }
        }
    }

    @Composable override fun StoredItem(item: HistoryItemViewModel, generation: Long, shellSessions: AgentShellSessionRegistry?) {
        val registry = LocalHistoryEntryFocus.current
        val focus = remember(item) { FocusRequester() }
        DisposableEffect(item, focus) {
            registry[item] = focus
            onDispose { if (registry[item] === focus) registry.remove(item) }
        }
        HistoryItemContent(item, MosaicHistoryItemRenderer(item, generation, focus, onOpenEntryContextMenu), shellSessions)
    }
    @Composable override fun Marker(value: String, failed: Boolean) {
        WrappedHistoryText(
            value, textStyle = if (failed) TextStyle.Unspecified else TextStyle.Dim,
            color = if (failed) TuiTheme.colorScheme.error else Color.Unspecified,
        )
    }
    override fun streamingContentChanged(model: AgentHistoryViewModel) {
        if (model.followsLatest.value && model.pendingScrollEffect.value == null) viewState.listState.requestScrollToStart()
    }
}

private val LocalHistoryEntryFocus = staticCompositionLocalOf<MutableMap<HistoryItemViewModel, FocusRequester>> {
    error("History list focus registry is not mounted")
}

private class MosaicHistoryListScope(private val original: io.github.stream29.kodex.cli.components.LazyListScope) : HistoryListScope {
    override fun Row(key: Any, contentType: Any, content: @Composable () -> Unit) { original.item(key, contentType, content) }
    override fun Committed(
        window: HistoryItemWindow, contentType: (HistoryItemViewModel) -> Any,
        content: @Composable (HistoryItemViewModel) -> Unit,
    ) {
        original.items(window.size, window::peek, { contentType(window.peek(it)) }) { content(window[it]) }
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
    HistoryItemContent(group, MosaicHistoryItemRenderer(group, generation, focusRequester, onOpenContextMenu), shellSessions)
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
    HistoryItemContent(item, MosaicHistoryItemRenderer(item, generation, focusRequester, onOpenContextMenu), shellSessions)
}

@Composable
internal fun CollapsedToolHistoryRow(
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
