package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorDialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.pathpicker.*

@Composable
public actual fun rememberComposeDirectoryPickerRenderer(): DirectoryPickerRenderer = remember { ComposeDirectoryPicker() }

private class ComposeDirectoryPicker : DirectoryPickerRenderer {
    @Composable
    override fun Dialog(
        presentation: DirectoryPickerPresentation, onDismiss: () -> Unit, onEscape: () -> Unit, onConfirm: () -> Unit,
        onNavigateUp: () -> Unit, onFilter: (String) -> Unit, onFilterLetter: (String) -> Unit, onBackspace: () -> Boolean,
    ) {
        val list = rememberLazyListState()
        LaunchedEffect(presentation.listingIdentity, presentation.filter) { list.requestScrollToItem(0) }
        ComposeDialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    Modifier.widthIn(max = 760.dp).heightIn(max = 720.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    UiText(presentation.title, UiTextRole.Title)
                    SelectionContainer { UiText(presentation.directory) }
                    UiTextInput(presentation.filter, onFilter, label = "Directory filter")
                    UiAction(presentation.selectLabel, autoFocus = true, onClick = onConfirm)
                    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp)) {
                        items(presentation.rows, key = DirectoryPickerRow::key) { row ->
                            when (row.role) {
                                DirectoryPickerRowRole.Parent, DirectoryPickerRowRole.Directory -> OutlinedButton(
                                    onClick = { row.onActivate?.invoke() }, modifier = Modifier.fillMaxWidth(),
                                ) { Text(row.label) }
                                DirectoryPickerRowRole.Text -> UiText(row.label)
                                DirectoryPickerRowRole.Error -> UiText(row.label, UiTextRole.Error)
                            }
                        }
                    }
                    UiActions { UiAction(presentation.cancelLabel, onClick = onDismiss) }
                }
            }
            NavigationBackHandler(
                state = rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onEscape,
            )
        }
    }
}
