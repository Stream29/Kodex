package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.ui.compose.ComposeHistoryViewState
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryListRenderer
import io.github.stream29.kodex.cli.history.AgentHistoryContent

/** Diagnostics around the product list, not a parallel message-only renderer or application entrypoint. */
@Composable
internal fun FixtureHistoryList(
    model: AgentHistoryViewModel, targetIndex: Int? = null, state: FixtureHistoryListState? = null,
) {
    val window by model.historyItems.collectAsState()
    val load by model.loadState.collectAsState()
    val follows by model.followsLatest.collectAsState()
    val effect by model.pendingScrollEffect.collectAsState()
    val diagnostics = state ?: remember(model) { FixtureHistoryListState(model) }
    check(diagnostics.model === model)
    val visible = diagnostics.visible
    UiText("History slice load: $load; bounded rows=${window.size}")
    UiText("History window: newest=${window.takeIf { it.size > 0 }?.peek(0)?.messageIndex()}; " +
        "oldest=${window.takeIf { it.size > 0 }?.peek(window.size - 1)?.messageIndex()}; " +
        "hasNewer=${window.hasNewer}; follows=$follows; effect=${effect != null}; acknowledgments=${diagnostics.acknowledgments}")
    UiText("History viewport: rows=${visible.size}; storage=${visible.mapNotNull { it.messageIndex() }.joinToString(",")}")
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        AgentHistoryContent(diagnostics.observed, rememberComposeHistoryListRenderer(diagnostics.view))
    }
    UiActions {
        UiAction("Fixture Latest", onClick = model::requestScrollToLatest)
        if (window.hasOlder) UiAction("Fixture older page", onClick = window::requestOlder)
        if (targetIndex != null) UiAction("Fixture seeded target", onClick = { model.requestScrollToStorageIndex(targetIndex) })
    }
}

internal class FixtureHistoryListState(val model: AgentHistoryViewModel) {
    val view = ComposeHistoryViewState()
    var visible by mutableStateOf(emptyList<HistoryItemViewModel>())
    var acknowledgments by mutableIntStateOf(0)
    val observed = object : AgentHistoryViewModel by model {
        override fun reportViewport(window: HistoryItemWindow, visibleItems: List<HistoryItemViewModel>) {
            visible = visibleItems
            model.reportViewport(window, visibleItems)
        }
        override fun acknowledgeScrollEffect(effect: HistoryScrollEffect) {
            val exact = model.pendingScrollEffect.value === effect
            model.acknowledgeScrollEffect(effect)
            if (exact && model.pendingScrollEffect.value !== effect) acknowledgments++
        }
    }
}

private fun HistoryItemViewModel.messageIndex(): Int? = (this as? MessageHistoryItemViewModel)?.index
