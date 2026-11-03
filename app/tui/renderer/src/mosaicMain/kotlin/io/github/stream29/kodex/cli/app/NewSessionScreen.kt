package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    statusBarRows: (Int) -> Int,
    onSubmit: (expectedRevision: Long) -> Unit,
    statusBar: @Composable (Int) -> Unit,
) {
    if (columns <= 0 || rows <= 0) return
    val groupWidth = minOf(columns, 80)
    val statusRows = statusBarRows(groupWidth).coerceAtLeast(0)
    val title = "Start a new session"
    val description = "Describe a task to work on together."
    // Leave an extra row for word wrapping at narrow widths.
    fun textRows(text: String): Int = (text.length + groupWidth - 1) / groupWidth +
        if (text.length > groupWidth) 1 else 0
    val titleRows = textRows(title)
    val descriptionRows = textRows(description)
    val bordered = groupWidth >= 5 && rows >= statusRows + 3
    val borderRows = if (bordered) 2 else 0
    val showTitle = rows >= statusRows + 1 + borderRows + titleRows
    val showDescription = showTitle && rows >= statusRows + 1 + borderRows + titleRows + descriptionRows
    val editorRows = (rows - statusRows - (if (showTitle) titleRows else 0) -
        (if (showDescription) descriptionRows else 0) - borderRows).coerceAtLeast(1)
    val scroll = remember { ScrollState() }
    val borderColor = TuiTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.width(columns).height(rows).clipToBounds(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.width(groupWidth).verticalScroll(scroll)) {
            if (showTitle) Text(title, textStyle = TuiTheme.typography.title)
            if (showDescription) Text(description, textStyle = TuiTheme.typography.supporting)
            NewSessionDraft(viewModel.composer, newLineKey, onSubmit, maximumEditorLines = editorRows) { content ->
                val decoration = if (bordered) Modifier.drawBehind {
                    drawText(0, 0, "┌" + "─".repeat((width - 2).coerceAtLeast(0)) + "┐", foreground = borderColor)
                    for (row in 1 until height - 1) {
                        drawText(row, 0, "│", foreground = borderColor)
                        drawText(row, width - 1, "│", foreground = borderColor)
                    }
                    drawText(height - 1, 0, "└" + "─".repeat((width - 2).coerceAtLeast(0)) + "┘", foreground = borderColor)
                }.padding(1) else Modifier
                Box(Modifier.width(groupWidth).then(decoration)) { content() }
            }
            statusBar(groupWidth)
        }
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
