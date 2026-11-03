package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.ui.UiEditorFocusHandle
import io.github.stream29.kodex.app.ui.WithUiEditorFocus
import io.github.stream29.kodex.app.ui.WithAdaptiveEditor
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.history.AgentHistoryContent
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun rememberComposeAgentPageRenderer(
    historyState: ComposeHistoryViewState,
    onOpenHistoryContextMenu: ((generation: Long, storageIndex: Int, item: HistoryItemViewModel, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
    composerFocus: UiEditorFocusHandle?,
    onOpenStreamingHistoryContextMenu: ((generation: Long, item: HistoryStreamingItem.Output, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
): AgentPageRenderer {
    val status = rememberComposeAgentStatusRenderer()
    val separator = rememberComposeAgentHistorySeparatorRenderer()
    return remember(historyState, onOpenHistoryContextMenu, onOpenStreamingHistoryContextMenu, status, separator, composerFocus) {
        ComposeAgentPageRenderer(historyState, onOpenHistoryContextMenu, status, separator, composerFocus, onOpenStreamingHistoryContextMenu)
    }
}

private class ComposeAgentPageRenderer(
    private val historyState: ComposeHistoryViewState,
    private val onOpenHistoryContextMenu: ((Long, Int, HistoryItemViewModel, ApplicationHistoryMenuPlacement) -> Unit)?,
    override val status: AgentStatusRenderer,
    override val separator: AgentHistorySeparatorRenderer,
    private val composerFocus: UiEditorFocusHandle?,
    private val onOpenStreamingHistoryContextMenu: ((Long, HistoryStreamingItem.Output, ApplicationHistoryMenuPlacement) -> Unit)?,
) : AgentPageRenderer {
    @Composable
    override fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val viewport = constraints.maxHeight
            Layout(
                modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
                    .semantics { contentDescription = "Agent page" }
                    .verticalScroll(rememberScrollState()).padding(8.dp),
                content = {
                    content(ComposeAgentPageScope(inputs, historyState, onOpenHistoryContextMenu, composerFocus, onOpenStreamingHistoryContextMenu))
                },
            ) { children, bounds ->
                val available = (viewport - 16.dp.roundToPx()).coerceAtLeast(0)
                val gap = 8.dp.roundToPx()
                val measured = arrayOfNulls<Placeable>(children.size)
                val history = children.indexOfFirst { it.layoutId == HistoryRegion }
                val childBounds = Constraints(maxWidth = bounds.maxWidth)
                children.forEachIndexed { index, child ->
                    if (index != history) measured[index] = child.measure(childBounds)
                }
                val fixedHeight = measured.sumOf { it?.height ?: 0 } + gap * (children.size - 1).coerceAtLeast(0)
                if (history >= 0) {
                    // Keep the virtual history finite. Overflowing auxiliary content belongs
                    // to the outer viewport, rather than measuring later controls at zero height.
                    val minimumHistory = minOf(96.dp.roundToPx(), available / 3)
                    val historyHeight = (available - fixedHeight).coerceAtLeast(minimumHistory)
                    measured[history] = children[history].measure(childBounds.copy(
                        minHeight = historyHeight, maxHeight = historyHeight,
                    ))
                }
                val contentHeight = measured.sumOf { it?.height ?: 0 } + gap * (children.size - 1).coerceAtLeast(0)
                layout(bounds.maxWidth, maxOf(available, contentHeight)) {
                    var y = 0
                    measured.forEach { child ->
                        child?.placeRelative(0, y)
                        y += (child?.height ?: 0) + gap
                    }
                }
            }
        }
    }
}

private class ComposeAgentPageScope(
    private val inputs: AgentPageMeasureInputs,
    private val historyState: ComposeHistoryViewState,
    private val onOpenHistoryContextMenu: ((Long, Int, HistoryItemViewModel, ApplicationHistoryMenuPlacement) -> Unit)?,
    private val composerFocus: UiEditorFocusHandle?,
    private val onOpenStreamingHistoryContextMenu: ((Long, HistoryStreamingItem.Output, ApplicationHistoryMenuPlacement) -> Unit)?,
) : AgentPageRenderScope {
    @Composable
    override fun History(viewModel: AgentHistoryViewModel, shellSessions: AgentShellSessionRegistry, canEdit: Boolean) {
        Box(Modifier.fillMaxWidth().layoutId(HistoryRegion).semantics { contentDescription = HistoryRegion }) {
            AgentHistoryContent(
                viewModel, rememberComposeHistoryListRenderer(historyState, onOpenHistoryContextMenu, canEdit, onOpenStreamingHistoryContextMenu),
                shellSessions,
            )
        }
    }

    @Composable
    override fun UserInput(viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending) {
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            RequestUserInputContent(viewModel, state, rememberComposeUserInputRenderer())
        }
    }

    @Composable
    override fun Suggestion(
        viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending,
        onBrowseWorkingDirectory: (String) -> Unit,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            SuggestSubagentTaskContent(viewModel, state, rememberComposeSuggestionRenderer(), onBrowseWorkingDirectory)
        }
    }

    @Composable
    override fun PendingSteer() {
        Column(Modifier.fillMaxWidth()) {
            pendingSteerPreviewContent(inputs.pendingSteer, maximumRows = 6).forEach { line ->
                Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    override fun Notice(message: String) { Text(message, style = MaterialTheme.typography.bodySmall) }

    @Composable
    override fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, enabled: Boolean) {
        @Composable fun Editor() {
            WithAdaptiveEditor {
                ComposerContent(viewModel, newLineKey, autoFocus = enabled, enabled = enabled, showPendingSteer = false, showFailure = false)
            }
        }
        if (composerFocus == null) Editor() else WithUiEditorFocus(composerFocus) { Editor() }
    }
}

private const val HistoryRegion = "Agent page history"
