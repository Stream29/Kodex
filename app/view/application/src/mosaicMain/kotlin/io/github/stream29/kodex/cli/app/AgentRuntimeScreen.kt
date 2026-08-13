package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.agent.RequestUserInputPanel
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.history.AgentHistoryUiState
import io.github.stream29.kodex.cli.history.AgentHistoryView
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.launch

@Composable
internal fun AgentRuntimeScreen(
    viewModel: AgentViewModel,
    historyUiState: AgentHistoryUiState,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onOpenHistoryEntryContextMenu: (
        target: AgentHistoryTarget,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val execution by viewModel.execution.collectAsState()
    val stream by viewModel.stream.collectAsState()
    val requestUserInput by viewModel.requestUserInput.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tokenCount by viewModel.tokenCount.collectAsState()
    val composerState by viewModel.composer.state.collectAsState()
    val composer = rememberComposerInputState(viewModel.composer)
    val composerLayout = TextInputLayout.create(
        value = composer.value,
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
    )
    val submitHint = submitToSteerHint(execution.running, composer.value.text)
    val composerRows = composerLayout.rowCount + if (submitHint == null) 0 else 1
    val availableContentRows =
        (rows - HistoryComposerSeparatorRows - composerRows - RuntimeStatusRows).coerceAtLeast(0)
    val pendingRequest = requestUserInput as? RequestUserInputState.Pending
    val requestUserInputRows = if (pendingRequest == null) {
        0
    } else {
        minOf(availableContentRows, RequestUserInputMaximumRows)
    }
    val pendingSteerLines = pendingSteerPreviewLines(
        pending = stream.pendingSteer,
        columns = columns,
        maximumRows = minOf(
            (availableContentRows - requestUserInputRows).coerceAtLeast(0),
            PendingSteerMaximumRows,
        ),
    )
    val historyRows = availableContentRows - requestUserInputRows - pendingSteerLines.size
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.width(columns).height(rows)) {
        Box(modifier = Modifier.width(columns).height(historyRows)) {
            AgentHistoryView(
                agentId = viewModel.address.agentId,
                model = viewModel.history,
                stream = stream,
                uiState = historyUiState,
                shellSessions = viewModel.shellSessions,
                onOpenEntryContextMenu = if (execution.capabilities.canReplaceHistory) {
                    { generation, storageIndex, anchor, position ->
                        onOpenHistoryEntryContextMenu(
                            AgentHistoryTarget(generation, storageIndex),
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
        if (pendingSteerLines.isNotEmpty()) {
            PendingSteerPreview(pendingSteerLines, columns)
        }
        HistoryComposerSeparator(columns)
        ComposerInput(
            state = composer,
            layout = composerLayout,
            newLineKey = newLineKey,
            autoFocus = pendingRequest == null,
            enabled = pendingRequest == null,
            submitHint = submitHint,
            onSubmit = {
                scope.launch { viewModel.submitComposer(composerState.revision) }
            },
            onValueChanged = { value ->
                viewModel.composer.update(value.text, value.cursorOffset)
            },
        )
        AgentRuntimeStatusBar(
            columns = columns,
            viewModel = viewModel,
            execution = execution,
            settings = settings,
            tokenCount = tokenCount,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
internal fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onSubmit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    Column(modifier = Modifier.width(columns).height(rows)) {
        NewSessionContent(
            composerViewModel = viewModel.composer,
            columns = columns,
            rows = (rows - RuntimeStatusRows).coerceAtLeast(0),
            newLineKey = newLineKey,
            onSubmit = onSubmit,
        )
        NewSessionStatusBar(
            columns = columns,
            viewModel = viewModel,
            settings = settings,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
internal fun NewSessionContent(
    composerViewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onSubmit: () -> Unit,
) {
    val composer = rememberComposerInputState(composerViewModel)
    val composerLayout = TextInputLayout.create(
        value = composer.value,
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
    )
    val historyRows =
        (rows - HistoryComposerSeparatorRows - composerLayout.rowCount).coerceAtLeast(0)
    Column(modifier = Modifier.width(columns).height(rows)) {
        Box(modifier = Modifier.width(columns).height(historyRows)) {
            Text("Enter a prompt to create a session", textStyle = TextStyle.Dim)
        }
        HistoryComposerSeparator(columns)
        ComposerInput(
            state = composer,
            layout = composerLayout,
            newLineKey = newLineKey,
            onSubmit = onSubmit,
            onValueChanged = { value ->
                composerViewModel.update(value.text, value.cursorOffset)
            },
        )
    }
}

@Composable
private fun rememberComposerInputState(composer: ComposerViewModel): TextInputState {
    val state by composer.state.collectAsState()
    val input = remember(composer) {
        TextInputState(TextInputValue(state.text, state.cursorOffset))
    }
    LaunchedEffect(composer, state) {
        val value = TextInputValue(state.text, state.cursorOffset)
        if (input.value != value) input.reset(value)
    }
    return input
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

internal fun pendingSteerPreviewLines(
    pending: List<StableCleanEvent.Steerable>,
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

private fun StableCleanEvent.Steerable.previewText(): String = when (this) {
    is StableCleanEvent.UserMessage -> content.previewContentText()
    is StableCleanEvent.AssistantMessage -> "Assistant: ${content.previewContentText()}"
    is StableCleanEvent.DeveloperMessage -> "Developer: ${content.previewContentText()}"
    is StableCleanEvent.AgentMessage -> "$author → $recipient: ${content.previewAgentMessageText()}"
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

private const val RuntimeStatusRows: Int = 1
private const val RequestUserInputMaximumRows: Int = 12
private const val PendingSteerMaximumRows: Int = 6
private const val SubmitToSteerHint: String = "Submit to steer"
