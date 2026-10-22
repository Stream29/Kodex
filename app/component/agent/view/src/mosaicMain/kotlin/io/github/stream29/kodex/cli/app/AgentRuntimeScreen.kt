package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.AgentNotificationLevel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.cli.agent.RequestUserInputPanel
import io.github.stream29.kodex.cli.agent.ComposerView
import io.github.stream29.kodex.cli.agent.boundedComposerRows
import io.github.stream29.kodex.cli.agent.composerStatusLine
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.agent.pendingSteerPreviewLines
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskPanel
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.history.AgentHistoryView
import io.github.stream29.kodex.cli.history.AgentHistoryViewState
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun AgentRuntimeScreen(
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
    composerFocusRequester: FocusRequester?,
    historyViewState: AgentHistoryViewState,
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
    val notificationLine = notification?.takeIf { it.level == AgentNotificationLevel.Information }?.let {
        listOfNotNull(it.message, it.detail).joinToString(" ").ellipsizeToTerminalWidth(columns)
    }
    val pendingRequest = requestUserInput as? RequestUserInputState.Pending
    val pendingSuggestion = suggestSubagentTask as? SuggestSubagentTaskState.Pending
    val hostInteractionPending = pendingRequest != null || pendingSuggestion != null
    val composerStatusRows = if (composerStatus == null) 0 else 1
    val notificationRows = if (notificationLine == null) 0 else 1
    val statusBarRows = agentRuntimeStatusBarRows(
        columns = columns,
        state = state,
        running = running,
        settings = settings,
        tokenCount = tokenCount,
    )
    val flexibleRows =
        (rows - HistoryComposerSeparatorRows - statusBarRows - composerStatusRows - notificationRows).coerceAtLeast(0)
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
        // Errors use the Application Hook outlet; only non-error Agent notices belong here.
        notificationLine?.let { Text(it, textStyle = TextStyle.Dim) }
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

private const val RequestUserInputMaximumRows: Int = 12
private const val PendingSteerMaximumRows: Int = 6
private const val SubmitToSteerHint: String = "Submit to steer"
