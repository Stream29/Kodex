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
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewState
import io.github.stream29.kodex.cli.agent.ComposerViewModel
import io.github.stream29.kodex.cli.agent.RequestUserInputPanel
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.history.AgentHistoryView
import io.github.stream29.kodex.cli.session.AgentRuntimeTreeEntry
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.launch

/** The complete content surface for one selected Agent runtime. */
@Composable
internal fun AgentRuntimeScreen(
    agent: AgentRuntimeTreeEntry,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    statusBar: @Composable (AgentRuntimeViewState) -> Unit,
) {
    val runtimeState by agent.viewModel.state.collectAsState()
    val requestUserInputState by agent.viewModel.requestUserInput.state.collectAsState()
    val pendingSteer by agent.viewModel.session.runtime.pendingSteer.collectAsState()
    val composer = rememberComposerInputState(agent.viewModel.composer)
    val composerLayout = TextInputLayout.create(
        value = composer.value,
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
    )
    val submitHint = submitToSteerHint(
        running = runtimeState.running,
        draft = composer.value.text,
    )
    val composerRows = composerLayout.rowCount + if (submitHint == null) 0 else 1
    val availableContentRows = (rows - HistoryComposerSeparatorRows - composerRows - RuntimeStatusRows)
        .coerceAtLeast(0)
    val requestUserInputRows = if (requestUserInputState.arguments == null) {
        0
    } else {
        minOf(availableContentRows, RequestUserInputMaximumRows)
    }
    val pendingSteerLines = pendingSteerPreviewLines(
        pending = pendingSteer,
        columns = columns,
        maximumRows = minOf(
            availableContentRows - requestUserInputRows,
            PendingSteerMaximumRows,
        ),
    )
    val historyRows = availableContentRows - requestUserInputRows - pendingSteerLines.size
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.width(columns).height(rows)) {
        Box(modifier = Modifier.width(columns).height(historyRows)) {
            AgentHistoryView(
                model = agent.historyViewModel,
                unifiedExecToolClient = agent.viewModel.session.runtime.unifiedExecToolClient,
            )
        }
        if (requestUserInputRows > 0) {
            RequestUserInputPanel(
                viewModel = agent.viewModel,
                state = requestUserInputState,
                columns = columns,
                rows = requestUserInputRows,
            )
        }
        if (pendingSteerLines.isNotEmpty()) {
            PendingSteerPreview(
                lines = pendingSteerLines,
                columns = columns,
            )
        }
        HistoryComposerSeparator(columns)
        ComposerInput(
            state = composer,
            layout = composerLayout,
            newLineKey = newLineKey,
            autoFocus = requestUserInputState.arguments == null,
            enabled = requestUserInputState.arguments == null,
            submitHint = submitHint,
            onSubmit = {
                scope.launch { agent.viewModel.submitComposer() }
            },
            onValueChanged = { value ->
                agent.viewModel.composer.update(value.text, value.cursorOffset)
            },
        )
        statusBar(runtimeState)
    }
}

/** The only non-runtime content surface, shown before the first root Agent exists. */
@Composable
internal fun NewSessionScreen(
    composerViewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onSubmit: () -> Unit,
    statusBar: @Composable () -> Unit,
) {
    val composer = rememberComposerInputState(composerViewModel)
    val composerLayout = TextInputLayout.create(
        value = composer.value,
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
    )
    val historyRows = (rows - HistoryComposerSeparatorRows - composerLayout.rowCount - RuntimeStatusRows)
        .coerceAtLeast(0)

    Column(modifier = Modifier.width(columns).height(rows)) {
        Box(modifier = Modifier.width(columns).height(historyRows)) {
            Text(
                value = "Enter a prompt to create a session",
                textStyle = TextStyle.Dim,
            )
        }
        HistoryComposerSeparator(columns)
        ComposerInput(
            state = composer,
            layout = composerLayout,
            newLineKey = newLineKey,
            onSubmit = {
                onSubmit()
            },
            onValueChanged = { value ->
                composerViewModel.update(value.text, value.cursorOffset)
            },
        )
        statusBar()
    }
}

@Composable
private fun rememberComposerInputState(composer: ComposerViewModel): TextInputState {
    val state by composer.state.collectAsState()
    val input = remember(composer) {
        TextInputState(TextInputValue(text = state.text, cursorOffset = state.cursorOffset))
    }

    LaunchedEffect(composer, state) {
        val value = TextInputValue(text = state.text, cursorOffset = state.cursorOffset)
        if (input.value != value) input.reset(value)
    }
    return input
}

@Composable
private fun PendingSteerPreview(
    lines: List<String>,
    columns: Int,
) {
    Column(modifier = Modifier.width(columns).height(lines.size)) {
        lines.forEachIndexed { index, line ->
            Text(
                value = line,
                textStyle = if (index == 0) TextStyle.Bold else TextStyle.Dim,
            )
        }
    }
}

internal fun submitToSteerHint(
    running: Boolean,
    draft: String,
): String? = SubmitToSteerHint.takeIf { running && draft.isNotBlank() }

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
    val visibleCount = if (pending.size > detailCapacity && detailCapacity > 1) {
        detailCapacity - 1
    } else {
        detailCapacity
    }
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
    joinToString(separator = " ") { part ->
        when (part) {
            is ContentItem.InputText -> part.text.singleLine()
            is ContentItem.OutputText -> part.text.singleLine()
            is ContentItem.InputImage -> "[image]"
        }
    }.trim()

private fun List<AgentMessageInputContent>.previewAgentMessageText(): String =
    joinToString(separator = " ") { part ->
        when (part) {
            is AgentMessageInputContent.InputText -> part.text.singleLine()
            is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
        }
    }.trim()

private fun String.singleLine(): String =
    lineSequence().joinToString(separator = " ") { line -> line.trim() }.trim()

private const val RuntimeStatusRows: Int = 1
private const val RequestUserInputMaximumRows: Int = 12
private const val PendingSteerMaximumRows: Int = 6
private const val SubmitToSteerHint: String = "Submit to steer"
