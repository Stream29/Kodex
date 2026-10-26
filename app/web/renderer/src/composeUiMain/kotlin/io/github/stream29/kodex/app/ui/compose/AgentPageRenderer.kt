package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.ui.UiEditorFocusHandle
import io.github.stream29.kodex.app.ui.WithUiEditorFocus
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.history.AgentHistoryContent
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun rememberComposeAgentPageRenderer(
    historyState: ComposeHistoryViewState,
    onOpenHistoryContextMenu: ((generation: Long, storageIndex: Int, item: HistoryItemViewModel, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
    composerFocus: UiEditorFocusHandle?,
): AgentPageRenderer {
    val status = rememberComposeAgentStatusRenderer()
    val separator = rememberComposeAgentHistorySeparatorRenderer()
    return remember(historyState, onOpenHistoryContextMenu, status, separator, composerFocus) {
        ComposeAgentPageRenderer(historyState, onOpenHistoryContextMenu, status, separator, composerFocus)
    }
}

private class ComposeAgentPageRenderer(
    private val historyState: ComposeHistoryViewState,
    private val onOpenHistoryContextMenu: ((Long, Int, HistoryItemViewModel, ApplicationHistoryMenuPlacement) -> Unit)?,
    override val status: AgentStatusRenderer,
    override val separator: AgentHistorySeparatorRenderer,
    private val composerFocus: UiEditorFocusHandle?,
) : AgentPageRenderer {
    @Composable
    override fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(ComposeAgentPageScope(this, inputs, historyState, onOpenHistoryContextMenu, composerFocus))
            }
        }
    }
}

private class ComposeAgentPageScope(
    private val column: ColumnScope,
    private val inputs: AgentPageMeasureInputs,
    private val historyState: ComposeHistoryViewState,
    private val onOpenHistoryContextMenu: ((Long, Int, HistoryItemViewModel, ApplicationHistoryMenuPlacement) -> Unit)?,
    private val composerFocus: UiEditorFocusHandle?,
) : AgentPageRenderScope {
    @Composable
    override fun History(viewModel: AgentHistoryViewModel, shellSessions: AgentShellSessionRegistry, canEdit: Boolean) {
        with(column) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AgentHistoryContent(
                    viewModel, rememberComposeHistoryListRenderer(historyState, if (canEdit) onOpenHistoryContextMenu else null),
                    shellSessions,
                )
            }
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
        pendingSteerPreviewContent(inputs.pendingSteer, maximumRows = 6).forEach { line ->
            Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    override fun Notice(message: String) { Text(message, style = MaterialTheme.typography.bodySmall) }

    @Composable
    override fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, enabled: Boolean) {
        @Composable fun Editor() {
            ComposerContent(viewModel, newLineKey, autoFocus = enabled, enabled = enabled, showPendingSteer = false, showFailure = false)
        }
        if (composerFocus == null) Editor() else WithUiEditorFocus(composerFocus) { Editor() }
    }
}
