package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.cli.agent.RequestUserInputPanel
import io.github.stream29.kodex.cli.agent.ComposerView
import io.github.stream29.kodex.cli.agent.composerStatusLine
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskPanel
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.history.AgentHistoryView
import io.github.stream29.kodex.cli.history.AgentHistoryViewState
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem

/**
 * Borrows the exact Agent and its existing children. Unmounting does not close them.
 * Menu callbacks retain the original generation, storage index and actual item.
 * [historyViewState] owns only borrowed renderer position/input; the root retains it across
 * tab unmounts by exact History identity and discards it on binding replacement or close.
 */
@Composable
public fun AgentRuntimeScreen(
    viewModel: AgentViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    dropdowns: RuntimeConfigurationDropdowns,
    suggestionDropdowns: SuggestSubagentTaskDropdowns,
    onOpenHistoryEntryContextMenu: (
        generation: Long,
        storageIndex: Int,
        item: io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit,
    onBrowseWorkingDirectory: () -> Unit,
    onBrowseSuggestedWorkingDirectory: (String) -> Unit,
    onOpenSettings: () -> Unit,
    composerFocusRequester: FocusRequester? = null,
    historyViewState: AgentHistoryViewState = remember(viewModel.history) { AgentHistoryViewState() },
) {
    val state by viewModel.state.collectAsState()
    val running by viewModel.running.collectAsState()
    val pendingSteer by viewModel.pendingSteer.collectAsState()
    val requestUserInput by viewModel.requestUserInput.state.collectAsState()
    val suggestSubagentTask by viewModel.suggestSubagentTask.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tokenCount by viewModel.tokenCount.collectAsState()
    val composerState by viewModel.composer.state.collectAsState()
    val notification by viewModel.notification.collectAsState()
    val activeTurnDuration by viewModel.history.activeTurnDuration.collectAsState()
    val followsLatest by viewModel.history.followsLatest.collectAsState()
    val composerText = composerState.text
    val fullComposerLayout = TextInputLayout.create(
        value = TextInputValue(
            text = composerText,
            cursorOffset = composerText.length,
        ),
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
        softWrap = true,
    )
    val composerStatus = composerStatusLine(composerState, showFailure = false)
    val failureLine = notification?.let {
        listOfNotNull(it.message, it.detail).joinToString(" ").ellipsizeToTerminalWidth(columns)
    }
    val pendingRequest = requestUserInput as? RequestUserInputState.Pending
    val pendingSuggestion = suggestSubagentTask as? SuggestSubagentTaskState.Pending
    val hostInteractionPending = pendingRequest != null || pendingSuggestion != null
    val composerStatusRows = if (composerStatus == null) 0 else 1
    val failureRows = if (failureLine == null) 0 else 1
    val statusBarRows = agentRuntimeStatusBarRows(
        columns = columns,
        state = state,
        running = running,
        settings = settings,
        tokenCount = tokenCount,
    )
    val flexibleRows =
        (rows - HistoryComposerSeparatorRows - statusBarRows - composerStatusRows - failureRows).coerceAtLeast(0)
    val minimumComposerRows = minOf(1, flexibleRows)
    val requestUserInputRows = if (!hostInteractionPending) {
        0
    } else {
        minOf(
            (flexibleRows - minimumComposerRows).coerceAtLeast(0),
            RequestUserInputMaximumRows,
        )
    }
    val pendingSteerLines = pendingSteerPreviewLines(
        pending = pendingSteer,
        columns = columns,
        maximumRows = minOf(
            (flexibleRows - minimumComposerRows - requestUserInputRows).coerceAtLeast(0),
            PendingSteerMaximumRows,
        ),
    )
    val composerAndHistoryRows =
        (flexibleRows - requestUserInputRows - pendingSteerLines.size).coerceAtLeast(0)
    val composerRows = boundedComposerRows(
        availableRows = composerAndHistoryRows,
        desiredRows = fullComposerLayout.rowCount,
    )
    val historyRows = (composerAndHistoryRows - composerRows).coerceAtLeast(0)

    Column(modifier = Modifier.width(columns).height(rows)) {
        Box(modifier = Modifier.width(columns).height(historyRows)) {
            AgentHistoryView(
                model = viewModel.history,
                shellSessions = viewModel.shellSessions,
                viewState = historyViewState,
                onOpenEntryContextMenu = if (
                    state.canEditHistory(running)
                ) {
                    { generation, storageIndex, item, anchor, position ->
                        onOpenHistoryEntryContextMenu(
                            generation,
                            storageIndex,
                            item,
                            anchor,
                            position,
                        )
                    }
                } else {
                    null
                },
            )
        }
        pendingRequest?.let { pending ->
            if (requestUserInputRows > 0) {
                RequestUserInputPanel(
                    viewModel = viewModel.requestUserInput,
                    state = pending,
                    columns = columns,
                    rows = requestUserInputRows,
                )
            }
        }
        pendingSuggestion?.let { pending ->
            if (requestUserInputRows > 0) {
                SuggestSubagentTaskPanel(
                    viewModel = viewModel.suggestSubagentTask,
                    state = pending,
                    columns = columns,
                    rows = requestUserInputRows,
                    dropdowns = suggestionDropdowns,
                    onBrowseWorkingDirectory = onBrowseSuggestedWorkingDirectory,
                )
            }
        }
        if (pendingSteerLines.isNotEmpty()) {
            PendingSteerPreview(pendingSteerLines, columns)
        }
        HistoryComposerSeparator(
            columns = columns,
            liveDuration = activeTurnDuration,
            showScrollToLatest = !followsLatest,
            onScrollToLatest = viewModel.history::requestScrollToLatest,
        )
        // The same Agent notification covers append/steer and caught asynchronous resume errors.
        // Composer keeps its failure state, but this host renders that failure only here.
        failureLine?.let { Text(it, textStyle = TextStyle.Dim) }
        ComposerView(
            viewModel = viewModel.composer,
            columns = columns,
            rows = composerRows + composerStatusRows,
            newLineKey = newLineKey,
            autoFocus = !hostInteractionPending,
            enabled = !hostInteractionPending,
            focusRequester = composerFocusRequester,
            showPendingSteer = false,
            showFailure = false,
        )
        AgentRuntimeStatusBar(
            columns = columns,
            viewModel = viewModel,
            state = state,
            running = running,
            settings = settings,
            tokenCount = tokenCount,
            dropdowns = dropdowns,
            onBrowseWorkingDirectory = onBrowseWorkingDirectory,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun PendingSteerPreview(lines: List<String>, columns: Int) {
    Column(modifier = Modifier.width(columns).height(lines.size)) {
        lines.forEachIndexed { index, line ->
            Text(line, textStyle = if (index == 0) TextStyle.Bold else TextStyle.Dim)
        }
    }
}

internal fun submitToSteerHint(running: Boolean, draft: String): String? =
    SubmitToSteerHint.takeIf { running && draft.isNotBlank() }

internal fun boundedComposerRows(availableRows: Int, desiredRows: Int): Int {
    require(desiredRows > 0) { "Composer content must contain at least one row." }
    return minOf(desiredRows, availableRows.coerceAtLeast(1))
}

internal fun pendingSteerPreviewLines(
    pending: List<StableIndexEvent.Steerable>,
    columns: Int,
    maximumRows: Int,
): List<String> {
    if (pending.isEmpty() || maximumRows <= 0) return emptyList()
    val width = columns.coerceAtLeast(1)
    val header = "Pending steer (${pending.size})".ellipsizeToTerminalWidth(width)
    if (maximumRows == 1) return listOf(header)
    val detailCapacity = maximumRows - 1
    val visibleCount =
        if (pending.size > detailCapacity && detailCapacity > 1) detailCapacity - 1 else detailCapacity
    return buildList {
        add(header)
        pending.take(visibleCount).forEach { steer ->
            add(("  ↳ " + steer.previewText()).ellipsizeToTerminalWidth(width))
        }
        if (pending.size > visibleCount && detailCapacity > 1) {
            add("  … ${pending.size - visibleCount} more".ellipsizeToTerminalWidth(width))
        }
    }
}

private fun StableIndexEvent.Steerable.previewText(): String = when (this) {
    is StableUserMessage -> content.previewContentText()
    is StableAssistantMessage -> "Assistant: ${content.previewContentText()}"
    is StableDeveloperMessage -> "Developer: ${content.previewContentText()}"
    is StableAgentMessage -> "$author → $recipient: ${content.previewAgentMessageText()}"
}.ifBlank { "[empty message]" }

private fun List<ContentItem>.previewContentText(): String =
    joinToString(" ") { part ->
        when (part) {
            is ContentItem.InputText -> part.text.singleLine()
            is ContentItem.OutputText -> part.text.singleLine()
            is ContentItem.InputImage -> "[image]"
        }
    }.trim()

private fun List<AgentMessageInputContent>.previewAgentMessageText(): String =
    joinToString(" ") { part ->
        when (part) {
            is AgentMessageInputContent.InputText -> part.text.singleLine()
            is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
        }
    }.trim()

private fun String.singleLine(): String =
    lineSequence().joinToString(" ") { line -> line.trim() }.trim()

private const val RequestUserInputMaximumRows: Int = 12
private const val PendingSteerMaximumRows: Int = 6
private const val SubmitToSteerHint: String = "Submit to steer"
