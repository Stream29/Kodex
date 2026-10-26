package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.cli.components.*
import kotlinx.io.files.Path

@Composable
public actual fun BoxScope.DirectoryPickerPopup(
    viewModel: DirectoryPickerViewModel, onDismissRequest: () -> Unit, onDirectorySelected: (Path) -> Unit,
) { DirectoryPickerPopup(viewModel, onDismissRequest, onDirectorySelected, closeOnDispose = true) }

@Composable
public actual fun BoxScope.DirectoryPickerPopup(
    viewModel: DirectoryPickerViewModel, onDismissRequest: () -> Unit, onDirectorySelected: (Path) -> Unit, closeOnDispose: Boolean,
) {
    DirectoryPickerContent(
        viewModel, remember(this) { MosaicDirectoryPickerRenderer(this) },
        onDismissRequest, onDirectorySelected, closeOnDispose,
    )
}

internal class MosaicDirectoryPickerRenderer(private val box: BoxScope) : DirectoryPickerRenderer {
    @Composable
    override fun Dialog(
        presentation: DirectoryPickerPresentation, onDismiss: () -> Unit, onEscape: () -> Unit, onConfirm: () -> Unit,
        onNavigateUp: () -> Unit, onFilter: (String) -> Unit, onFilterLetter: (String) -> Unit, onBackspace: () -> Boolean,
    ) {
        with(box) { DirectoryDialog(presentation, onDismiss, onEscape, onConfirm, onNavigateUp, onFilterLetter, onBackspace) }
    }
}

@Composable
private fun BoxScope.DirectoryDialog(
    presentation: DirectoryPickerPresentation, onDismiss: () -> Unit, onEscape: () -> Unit, onConfirm: () -> Unit,
    onNavigateUp: () -> Unit, onFilterLetter: (String) -> Unit, onBackspace: () -> Boolean,
) {
    val terminal = LocalTerminalState.current
    val list = rememberLazyListState()
    val first = presentation.rows.firstOrNull { it.focusForFilter }?.key
    val firstFocus = remember(first) { FocusRequester() }
    LaunchedEffect(presentation.listingIdentity) { list.requestScrollToStart() }
    LaunchedEffect(presentation.filter, first) {
        if (presentation.filter.isNotEmpty()) {
            list.requestScrollToStart()
            if (first != null) { withFrameNanos { }; firstFocus.requestFocus() }
        }
    }
    val width = (terminal.size.columns - 4).coerceIn(1, 84)
    val listRows = (terminal.size.rows - 9).coerceIn(1, 16)
    TuiDialog(
        onDismissRequest = onDismiss, onEscapeRequest = onEscape,
        modifier = Modifier.width(width).background(DirectoryBackground)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == "Backspace" && !event.ctrl && !event.alt -> onBackspace()
                    !event.ctrl && !event.alt && event.key.length == 1 && event.key.single().isLetter() -> {
                        onFilterLetter(event.key); true
                    }
                    else -> false
                }
            }
            .onPreviewPointerEvent { event ->
                if (event.type == MouseEvent.Type.Press && event.button == MouseEvent.Button.Button8) {
                    onNavigateUp(); true
                } else false
            },
    ) {
        Column(Modifier.fillMaxWidth().background(DirectoryBackground)) {
            Text(
                presentation.title, Modifier.fillMaxWidth().background(TuiTheme.colorScheme.primary),
                color = TuiTheme.colorScheme.onPrimary, textStyle = TuiTheme.typography.headline,
            )
            Text(presentation.directory.ellipsizeToTerminalWidth(width), Modifier.fillMaxWidth().background(DirectoryBackground), color = DirectoryForeground)
            Text(
                "Filter: ${presentation.filter.ifEmpty { "type letters" }}".ellipsizeToTerminalWidth(width),
                Modifier.fillMaxWidth().background(DirectoryBackground), color = DirectoryForeground,
            )
            Row(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.primary)) {
                TuiButton(presentation.selectLabel, color = TuiTheme.colorScheme.onPrimary, autoFocus = true, onClick = onConfirm)
            }
            Box(Modifier.fillMaxWidth().height(listRows).background(DirectoryBackground)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(listRows)) {
                    items(presentation.rows, key = DirectoryPickerRow::key) { row ->
                        when (row.role) {
                            DirectoryPickerRowRole.Parent, DirectoryPickerRowRole.Directory -> TuiButton(
                                label = if (row.role == DirectoryPickerRowRole.Parent) row.label
                                    else row.label.ellipsizeToTerminalWidth((width - 2).coerceAtLeast(1)),
                                modifier = Modifier.fillMaxWidth().background(DirectoryBackground), color = DirectoryForeground,
                                focusRequester = if (row.focusForFilter) firstFocus else null,
                                onClick = { row.onActivate?.invoke() },
                            )
                            DirectoryPickerRowRole.Text -> Text(row.label, color = DirectoryForeground)
                            DirectoryPickerRowRole.Error -> Text(row.label.ellipsizeToTerminalWidth(width), color = DirectoryForeground)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.primary)) {
                TuiButton(presentation.cancelLabel, color = TuiTheme.colorScheme.onPrimary, onClick = onDismiss)
            }
        }
    }
}

private val DirectoryBackground: Color
    @Composable @ReadOnlyComposable get() = TuiTheme.colorScheme.surfaceContainer
private val DirectoryForeground: Color
    @Composable @ReadOnlyComposable get() = TuiTheme.colorScheme.onSurface
