package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Renders the exact draft [viewModel], without introducing a second page state or creation API.
 *
 * [statusBarRows] and [statusBar] are visual layout inputs for the host's shared runtime controls.
 * [onSubmit] targets this exact draft through the registry owner, which replaces its tab after
 * materialization. The renderer does not allocate, wrap the persisted result, replay a creation,
 * or close the draft when selection unmounts it. The registry alone owns its lifetime.
 */
@Composable
public fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    statusBarRows: Int,
    onSubmit: () -> Unit,
    statusBar: @Composable () -> Unit,
) {
    Column(modifier = Modifier.width(columns).height(rows)) {
        NewSessionContent(
            composerViewModel = viewModel.composer,
            columns = columns,
            rows = (rows - statusBarRows).coerceAtLeast(0),
            newLineKey = newLineKey,
            onSubmit = onSubmit,
        )
        statusBar()
    }
}

/**
 * Draft input with the original blank-history space, separator, bounded wrapping and cursor.
 *
 * The widget buffer belongs to the renderer; accepted edits are sent to the one Composer child.
 * Submit invokes the captured owner's [onSubmit], not Composer's persisted-Agent submission port.
 * A tab switch disposes only widget state, never the borrowed Composer or its owner.
 */
@Composable
public fun NewSessionContent(
    composerViewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onSubmit: () -> Unit,
) {
    val state by composerViewModel.state.collectAsState()
    val input = remember(composerViewModel) {
        TextInputState(TextInputValue(state.text, state.cursorOffset))
    }
    LaunchedEffect(composerViewModel, state) {
        if (input.value.text != state.text || input.value.cursorOffset != state.cursorOffset) {
            input.reset(TextInputValue(state.text, state.cursorOffset))
        }
    }
    val fullLayout = TextInputLayout.create(
        value = input.value,
        width = columns,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
        softWrap = true,
    )
    val availableRows = (rows - 1).coerceAtLeast(0)
    val composerRows = minOf(fullLayout.rowCount, availableRows.coerceAtLeast(1))
    val layout = fullLayout.withViewportRows(composerRows)
    Column(modifier = Modifier.width(columns).height(rows)) {
        Box(modifier = Modifier.width(columns).height((availableRows - composerRows).coerceAtLeast(0))) {}
        Text("-".repeat(columns.coerceAtLeast(1)), textStyle = TuiTheme.typography.supporting)
        ComposerInput(
            state = input,
            layout = layout,
            newLineKey = newLineKey,
            autoFocus = true,
            onValueChanged = { value -> composerViewModel.update(value.text, value.cursorOffset) },
            onSubmit = onSubmit,
        )
    }
}
