@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

internal val LocalHistoryEntryFocus = staticCompositionLocalOf<MutableMap<HistoryItemViewModel, FocusRequester>?> { null }

public actual class ComposeHistoryViewState actual constructor() {
    internal val list = LazyListState()
    internal val markdownModes = AssistantMarkdownModes()
    internal var owner: AgentHistoryViewModel? = null
    internal var mount: Any? = null
}

internal fun ComposeHistoryViewState.canToggleStoredMarkdown(
    model: AgentHistoryViewModel, generation: Long, index: Int, item: HistoryItemViewModel,
): Boolean {
    if (owner !== model || mount == null || !model.contains(generation, index)) return false
    val message = item as? MessageHistoryItemViewModel ?: return false
    if (message.index != index || (message.state.value as? MessageHistoryItemState.Ready)?.event !is StableAssistantMessage) return false
    val current = model.historyItems.value
    return current.generation == generation && (0 until current.size).any { current.peek(it) === item }
}

internal fun ComposeHistoryViewState.canToggleStreamingMarkdown(
    model: AgentHistoryViewModel, generation: Long, item: HistoryStreamingItem.Output,
): Boolean {
    if (owner !== model || mount == null || model.historyItems.value.generation != generation) return false
    val current = model.streamingItem.value as? HistoryStreamingItem.Output ?: return false
    return current.kind == HistoryStreamingKind.Message && item.kind == HistoryStreamingKind.Message &&
        current.events === item.events && markdownModes.streamIsEligible(generation, item.events)
}

@Composable
public actual fun rememberComposeHistoryListRenderer(
    state: ComposeHistoryViewState,
    onOpenContextMenu: ((generation: Long, storageIndex: Int, item: HistoryItemViewModel, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
    canEditHistory: Boolean,
    onOpenStreamingMenu: ((generation: Long, item: HistoryStreamingItem.Output, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
): HistoryListRenderer {
    val base = rememberComposeHistoryContentRenderer()
    val callback = rememberUpdatedState(onOpenContextMenu)
    val editable = rememberUpdatedState(canEditHistory)
    val streamingMenu = rememberUpdatedState(onOpenStreamingMenu)
    return remember(state, base) { ComposeHistoryListRenderer(state, base, callback, editable, streamingMenu) }
}

private class ComposeHistoryListRenderer(
    private val state: ComposeHistoryViewState, base: HistoryContentRenderer,
    private val menu: State<((Long, Int, HistoryItemViewModel, ApplicationHistoryMenuPlacement) -> Unit)?>,
    private val editable: State<Boolean>,
    private val streamingMenu: State<((Long, HistoryStreamingItem.Output, ApplicationHistoryMenuPlacement) -> Unit)?>,
) : HistoryListRenderer, HistoryContentRenderer by base {
    @Composable override fun List(
        model: AgentHistoryViewModel, window: HistoryItemWindow, followsLatest: Boolean,
        effect: HistoryScrollEffect?, firstCommittedIndex: Int, latestKey: Any?,
        content: (HistoryListScope) -> Unit,
    ) {
        val list = state.list
        val scope = rememberCoroutineScope()
        val entryFocus = remember(model) { mutableMapOf<HistoryItemViewModel, FocusRequester>() }
        val currentWindow by rememberUpdatedState(window)
        val streaming = model.streamingItem.collectAsState().value as? HistoryStreamingItem.Output
        var lastUserScrollDelta by remember(model, state, window.generation) { mutableFloatStateOf(0f) }
        LaunchedEffect(state, model, window.generation) {
            state.markdownModes.retainGeneration(window.generation)
        }
        LaunchedEffect(state, model, window.generation, HistoryStreamingIdentity(streaming?.events), streaming?.kind) {
            state.markdownModes.retainStream(
                window.generation, streaming?.takeIf { it.kind == HistoryStreamingKind.Message }?.events,
            )
        }
        DisposableEffect(model, state) {
            check(state.owner == null || state.owner === model) { "History widget state belongs to another owner" }
            check(state.mount == null) { "History widget state is already mounted" }
            val mount = Any()
            state.owner = model
            state.mount = mount
            onDispose {
                if (state.mount === mount) state.mount = null
                model.reportViewport(model.historyItems.value, emptyList())
            }
        }
        LaunchedEffect(model, state, window, effect, latestKey, firstCommittedIndex) {
            if (effect != null) lastUserScrollDelta = 0f
            var previousAnchor: LazyListItemInfo? = null
            snapshotFlow { list.layoutInfo to lastUserScrollDelta }.collect { (layout, delta) ->
                val anchor = layout.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
                val previous = previousAnchor
                previousAnchor = anchor
                if (anchor != null && previous?.key == anchor.key && previous.size != anchor.size &&
                    !model.followsLatest.value && model.pendingScrollEffect.value == null &&
                    model.historyItems.value === window && !list.isScrollInProgress
                ) {
                    // Reverse layout retains distance from the row's bottom. Preserve its reading
                    // position from the top instead when an already-visible row changes height.
                    list.requestScrollToItem(anchor.index,
                        (list.firstVisibleItemScrollOffset + anchor.size - previous.size).coerceAtLeast(0))
                }
                model.reportViewport(window, layout.visibleItemsInfo.mapNotNull { it.key as? HistoryItemViewModel })
                // The last newer page can arrive after the input animation has already stopped.
                // Settle that genuine return only against the new provider's measured latest key.
                if (delta < 0f && !window.hasNewer && model.loadState.value == AgentHistoryLoadState.Ready &&
                    model.historyItems.value === window &&
                    model.pendingScrollEffect.value == null && latestKey != null &&
                    list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 &&
                    layout.visibleItemsInfo.any { it.index == 0 && it.key == latestKey }
                ) {
                    lastUserScrollDelta = 0f
                    model.setFollowsLatest(window, true)
                }
            }
        }
        LaunchedEffect(model, state, window, followsLatest, effect, firstCommittedIndex) {
            if (followsLatest && !window.hasNewer && effect == null) list.requestScrollToItem(0)
        }
        LaunchedEffect(model, state, window, effect, firstCommittedIndex, latestKey) {
            val expected = effect ?: return@LaunchedEffect
            if (expected.generation != window.generation || model.pendingScrollEffect.value !== expected) return@LaunchedEffect
            val target = expected.target
            val index = when (target) {
                HistoryScrollTarget.Latest -> 0
                is HistoryScrollTarget.Item -> (0 until window.size).firstOrNull { window.peek(it) === target.item }
                    ?.plus(firstCommittedIndex) ?: return@LaunchedEffect
            }
            val children = kotlin.collections.List(window.size) { window.peek(it) }
            while (true) {
                val before = list.layoutInfo
                list.requestScrollToItem(index)
                val measured = snapshotFlow { list.layoutInfo }.first { it !== before }
                val loading = measured.visibleItemsInfo.mapNotNull { it.key as? HistoryItemViewModel }
                    .filter { item -> children.any { it === item } }.flatMap { it.initialLoadingJobs() }
                if (loading.isNotEmpty()) {
                    loading.forEach { it.join() }
                    withFrameNanos { }
                    continue
                }
                val reached = when (target) {
                    HistoryScrollTarget.Latest -> latestKey != null && measured.visibleItemsInfo.any { it.index == 0 && it.key == latestKey } &&
                        list.firstVisibleItemScrollOffset == 0
                    is HistoryScrollTarget.Item -> measured.visibleItemsInfo.any { it.key === target.item }
                }
                if (reached) break
            }
            if (model.pendingScrollEffect.value === expected && model.historyItems.value === window &&
                window.generation == expected.generation
            ) model.acknowledgeScrollEffect(expected)
        }
        val gesture = remember(model, state, window.generation) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                        val newest = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
                        lastUserScrollDelta = if (currentWindow.hasNewer && model.pendingScrollEffect.value == null) consumed.y else 0f
                        model.setFollowsLatest(currentWindow, newest && !currentWindow.hasNewer)
                    }
                    return Offset.Zero
                }
            }
        }
        CompositionLocalProvider(LocalHistoryEntryFocus provides entryFocus) {
            LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize().nestedScroll(gesture)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.isShiftPressed || event.isCtrlPressed ||
                        event.isAltPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.PageUp -> 1
                        Key.PageDown -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (if (direction > 0) !list.canScrollForward else !list.canScrollBackward)
                        return@onPreviewKeyEvent false
                    val generation = currentWindow.generation
                    scope.launch {
                        val viewport = list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset
                        val consumed = list.scrollBy(direction * (viewport / 2f).coerceAtLeast(1f))
                        if (consumed == 0f || state.owner !== model || model.historyItems.value.generation != generation)
                            return@launch
                        val newest = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
                        lastUserScrollDelta = if (currentWindow.hasNewer && model.pendingScrollEffect.value == null) -consumed else 0f
                        model.setFollowsLatest(currentWindow, newest && !currentWindow.hasNewer)
                        withFrameNanos { }
                        val layout = list.layoutInfo
                        val candidates = layout.visibleItemsInfo.filter {
                            it.key is HistoryItemViewModel && it.key !is WorkGroupHistoryItemViewModel &&
                                it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
                        }
                        val target = if (direction > 0) candidates.maxByOrNull { it.offset + it.size }
                            else candidates.minByOrNull { it.offset }
                        (target?.key as? HistoryItemViewModel)?.let { entryFocus[it]?.requestFocus() }
                    }
                    true
                }) {
                content(ComposeHistoryListScope(this))
            }
        }
    }
    @Composable override fun StoredItem(item: HistoryItemViewModel, generation: Long, shellSessions: AgentShellSessionRegistry?) {
        val message = item as? MessageHistoryItemViewModel
        val messageState = message?.state?.collectAsState()?.value
        val displayable = (messageState as? MessageHistoryItemState.Ready)?.event is StableAssistantMessage
        val callback = if (menu.value == null || (!editable.value && !displayable)) null else { child: HistoryItemViewModel, index: Int, placement: ApplicationHistoryMenuPlacement ->
            menu.value?.invoke(generation, index, child, placement)
            Unit
        }
        val messageIndex = message?.index
        CompositionLocalProvider(
            LocalAssistantMarkdownRaw provides (messageIndex != null && state.markdownModes.storedIsRaw(generation, messageIndex)),
        ) {
            HistoryItemContent(item, rememberComposeHistoryItemRenderer(callback), shellSessions)
        }
    }
    @Composable override fun StreamingItem(
        model: AgentHistoryViewModel, generation: Long, item: HistoryStreamingItem,
        content: @Composable () -> Unit,
    ) {
        val output = item as? HistoryStreamingItem.Output
        if (output == null || output.kind != HistoryStreamingKind.Message) {
            content()
            return
        }
        val origin = remember(model, HistoryStreamingIdentity(output.events)) { ComposeMenuOrigin() }
        val eligible = state.markdownModes.streamIsEligible(generation, output.events)
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        Box(
            Modifier.fillMaxWidth().semantics {
                if (eligible) contentDescription = "Streaming assistant message"
            }
                .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                    val open = streamingMenu.value
                    if (open != null && event.buttons.isSecondaryPressed &&
                        state.canToggleStreamingMarkdown(model, generation, output)
                    ) origin.bounds?.let { bounds ->
                        event.changes.forEach { it.consume() }
                        open(generation, output, ComposeHistoryMenuPlacement(origin, bounds, event.changes.firstOrNull()?.position))
                    }
                }
                .onPreviewKeyEvent { event ->
                    val open = streamingMenu.value
                    val bounds = origin.bounds
                    if (open != null && event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Menu || event.key == Key.F10 && event.isShiftPressed) &&
                        bounds != null && state.canToggleStreamingMarkdown(model, generation, output)
                    ) {
                        open(generation, output, ComposeHistoryMenuPlacement(origin, bounds, null))
                        true
                    } else false
                }
                .focusable(enabled = streamingMenu.value != null && eligible),
        ) {
            CompositionLocalProvider(
                LocalAssistantMarkdownRaw provides state.markdownModes.streamIsRaw(generation, output.events),
                LocalAssistantStreamEligibility provides { eligible ->
                    val current = model.streamingItem.value as? HistoryStreamingItem.Output
                    if (eligible != null && (state.owner == null || state.owner === model) && model.historyItems.value.generation == generation &&
                        current != null && current.kind == HistoryStreamingKind.Message && current.events === output.events
                    ) state.markdownModes.reportStreamEligibility(generation, output.events, eligible)
                },
            ) { content() }
        }
    }
    @Composable override fun Marker(value: String, failed: Boolean) {
        Text(value, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    override fun streamingContentChanged(model: AgentHistoryViewModel) {
        if (model.followsLatest.value && model.pendingScrollEffect.value == null) state.list.requestScrollToItem(0)
    }
}

private class HistoryStreamingIdentity(private val events: Any?) {
    override fun equals(other: Any?): Boolean = other is HistoryStreamingIdentity && other.events === events
    override fun hashCode(): Int = 0
}

private class ComposeHistoryListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : HistoryListScope {
    override fun Row(key: Any, contentType: Any, content: @Composable () -> Unit) {
        original.item(key, contentType) { content() }
    }
    override fun Committed(
        window: HistoryItemWindow, contentType: (HistoryItemViewModel) -> Any,
        content: @Composable (HistoryItemViewModel) -> Unit,
    ) {
        original.items(window.size, window::peek, { contentType(window.peek(it)) }) { content(window[it]) }
    }
}

private fun HistoryItemViewModel.initialLoadingJobs(): List<Job> = when (this) {
    is MessageHistoryItemViewModel -> listOfNotNull((state.value as? MessageHistoryItemState.Loading)?.loadingJob)
    is ToolHistoryItemViewModel -> listOfNotNull((state.value as? ToolHistoryItemState.Loading)?.loadingJob)
    is PatchHistoryItemViewModel -> listOfNotNull((state.value as? PatchHistoryItemState.Loading)?.loadingJob)
    is PlanUpdateHistoryItemViewModel -> listOfNotNull((state.value as? PlanUpdateHistoryItemState.Loading)?.loadingJob)
    is RequestUserInputHistoryItemViewModel -> listOfNotNull((state.value as? RequestUserInputHistoryItemState.Loading)?.loadingJob)
    is SuggestSubagentTaskHistoryItemViewModel -> listOfNotNull((state.value as? SuggestSubagentTaskHistoryItemState.Loading)?.loadingJob)
    is WorkGroupHistoryItemViewModel -> when (val current = state.value) {
        is WorkGroupHistoryItemState.Loading -> listOf(current.loadingJob)
        is WorkGroupHistoryItemState.Expanded -> current.children.flatMap { it.initialLoadingJobs() }
        else -> emptyList()
    }
    is ReasoningHistoryItemViewModel, is ContextCompactionHistoryItemViewModel -> emptyList()
}
