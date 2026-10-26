@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.*
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

public actual class ComposeHistoryViewState actual constructor() {
    internal val list = LazyListState()
    internal var owner: AgentHistoryViewModel? = null
    internal var mount: Any? = null
}

@Composable
public actual fun rememberComposeHistoryListRenderer(
    state: ComposeHistoryViewState,
    onOpenContextMenu: ((generation: Long, storageIndex: Int, item: HistoryItemViewModel, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
): HistoryListRenderer {
    val base = rememberComposeHistoryContentRenderer()
    val callback = rememberUpdatedState(onOpenContextMenu)
    return remember(state, base) { ComposeHistoryListRenderer(state, base, callback) }
}

private class ComposeHistoryListRenderer(
    private val state: ComposeHistoryViewState, base: HistoryContentRenderer,
    private val menu: State<((Long, Int, HistoryItemViewModel, ApplicationHistoryMenuPlacement) -> Unit)?>,
) : HistoryListRenderer, HistoryContentRenderer by base {
    @Composable override fun List(
        model: AgentHistoryViewModel, window: HistoryItemWindow, followsLatest: Boolean,
        effect: HistoryScrollEffect?, firstCommittedIndex: Int, latestKey: Any?,
        content: (HistoryListScope) -> Unit,
    ) {
        val list = state.list
        val currentWindow by rememberUpdatedState(window)
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
        LaunchedEffect(model, state, window) {
            snapshotFlow { list.layoutInfo }.collect { layout ->
                model.reportViewport(window, layout.visibleItemsInfo.mapNotNull { it.key as? HistoryItemViewModel })
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
        val gesture = remember(model, state) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                        val newest = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
                        model.setFollowsLatest(currentWindow, newest && !currentWindow.hasNewer)
                    }
                    return Offset.Zero
                }
            }
        }
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize().nestedScroll(gesture)) {
            content(ComposeHistoryListScope(this))
        }
    }
    @Composable override fun StoredItem(item: HistoryItemViewModel, generation: Long, shellSessions: AgentShellSessionRegistry?) {
        val callback = if (menu.value == null) null else { child: HistoryItemViewModel, index: Int, placement: ApplicationHistoryMenuPlacement ->
            menu.value?.invoke(generation, index, child, placement)
            Unit
        }
        HistoryItemContent(item, rememberComposeHistoryItemRenderer(callback), shellSessions)
    }
    @Composable override fun Marker(value: String, failed: Boolean) {
        Text(value, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    override fun streamingContentChanged(model: AgentHistoryViewModel) {
        if (model.followsLatest.value && model.pendingScrollEffect.value == null) state.list.requestScrollToItem(0)
    }
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
