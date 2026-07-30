package io.github.stream29.codex.lite.cli.app

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
import io.github.stream29.codex.lite.cli.agent.AgentRuntimeViewState
import io.github.stream29.codex.lite.cli.agent.ComposerViewModel
import io.github.stream29.codex.lite.cli.agent.RequestUserInputPanel
import io.github.stream29.codex.lite.cli.components.TextInputLayout
import io.github.stream29.codex.lite.cli.components.TextInputState
import io.github.stream29.codex.lite.cli.components.TextInputValue
import io.github.stream29.codex.lite.cli.history.AgentHistoryView
import io.github.stream29.codex.lite.cli.session.AgentRuntimeTreeEntry
import io.github.stream29.codex.lite.cli.settings.NewLineKey
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
    val composer = rememberComposerInputState(agent.viewModel.composer)
    val composerLayout = TextInputLayout.create(
        value = composer.value,
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
    )
    val availableContentRows = (rows - HistoryComposerSeparatorRows - composerLayout.rowCount - RuntimeStatusRows)
        .coerceAtLeast(0)
    val requestUserInputRows = if (requestUserInputState.arguments == null) {
        0
    } else {
        minOf(availableContentRows, RequestUserInputMaximumRows)
    }
    val historyRows = availableContentRows - requestUserInputRows
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
        HistoryComposerSeparator(columns)
        ComposerInput(
            state = composer,
            layout = composerLayout,
            newLineKey = newLineKey,
            autoFocus = requestUserInputState.arguments == null,
            enabled = requestUserInputState.arguments == null,
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
    onSubmit: suspend () -> Boolean,
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
    val scope = rememberCoroutineScope()

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
                scope.launch { onSubmit() }
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

private const val RuntimeStatusRows: Int = 1
private const val RequestUserInputMaximumRows: Int = 12
