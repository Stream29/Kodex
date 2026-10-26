package io.github.stream29.kodex.cli.sessionrename

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessionrename.SessionRenameContent
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme

@Composable
public actual fun BoxScope.SessionRenamePopup(
    viewModel: SessionRenameViewModel,
    onDismissRequest: () -> Unit,
    onSubmitted: () -> Unit,
    presentation: SessionRenamePresentation,
) {
    val width = if (presentation == SessionRenamePresentation.Labeled) {
        (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    } else 48
    SessionRenameContent(viewModel, onSubmitted, presentation, frame = { content ->
        TuiDialog(
            onDismissRequest = onDismissRequest,
            modifier = Modifier.width(width).background(TuiTheme.colorScheme.surfaceContainer),
        ) { content() }
    })
}
