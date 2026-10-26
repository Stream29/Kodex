package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    statusBarRows: Int,
    onSubmit: (expectedRevision: Long) -> Unit,
    statusBar: @Composable () -> Unit,
) {
    Column(modifier = Modifier.width(columns).height(rows).clipToBounds()) {
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

@Composable
public actual fun NewSessionContent(
    composerViewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    onSubmit: (expectedRevision: Long) -> Unit,
) {
    NewSessionDraft(
        composerViewModel, newLineKey, onSubmit, maximumEditorLines = (rows - 1).coerceAtLeast(1),
        frame = { content ->
            Box(
                modifier = Modifier.width(columns.coerceAtLeast(1)).height(rows.coerceAtLeast(0)).clipToBounds(),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column {
                    Text("-".repeat(columns.coerceAtLeast(1)), textStyle = TuiTheme.typography.supporting)
                    content()
                }
            }
        },
    )
}
