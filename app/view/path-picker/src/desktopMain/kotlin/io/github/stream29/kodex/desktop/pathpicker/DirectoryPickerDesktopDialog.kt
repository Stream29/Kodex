package io.github.stream29.kodex.desktop.pathpicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.canConfirm
import io.github.stream29.kodex.app.pathpicker.contract.canNavigateUp
import io.github.stream29.kodex.app.pathpicker.contract.currentDirectory
import io.github.stream29.kodex.app.pathpicker.contract.visibleChildren
import io.github.stream29.kodex.desktop.components.DesktopModal
import kotlinx.coroutines.flow.collect
import kotlinx.io.files.Path

/** Material Desktop directory browser shared by application and Settings popups. */
@Composable
public fun DirectoryPickerDesktopDialog(
    viewModel: DirectoryPickerViewModel,
    onDismissRequest: () -> Unit,
    onDirectorySelected: (Path) -> Unit,
): Unit {
    val state by viewModel.state.collectAsState()
    val currentOnDirectorySelected by rememberUpdatedState(onDirectorySelected)

    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DirectoryPickerEffect.DirectorySelected ->
                    currentOnDirectorySelected(effect.directory)
            }
        }
    }

    DesktopModal(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(700.dp).heightIn(min = 480.dp, max = 720.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape &&
                        state.filterQuery.isNotEmpty()
                    ) {
                        viewModel.clearFilter()
                        true
                    } else {
                        false
                    }
                },
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RectangleShape,
                ) {
                    Text(
                        text = "Select directory",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RectangleShape,
                ) {
                    Text(
                        text = state.currentDirectory.toString(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextField(
                    value = state.filterQuery,
                    onValueChange = viewModel::updateFilter,
                    modifier = Modifier.fillMaxWidth(),
                    prefix = { Text("Filter:") },
                    placeholder = { Text("type letters") },
                    singleLine = true,
                    trailingIcon = if (state.filterQuery.isEmpty()) {
                        null
                    } else {
                        {
                            TextButton(onClick = viewModel::clearFilter) {
                                Text("Clear")
                            }
                        }
                    },
                    shape = RectangleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RectangleShape,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        TextButton(
                            onClick = viewModel::confirm,
                            enabled = state.canConfirm,
                        ) {
                            Text("Select")
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (state.canNavigateUp) {
                        item(key = "parent") {
                            DirectoryRow(
                                label = "..",
                                onClick = viewModel::navigateUp,
                            )
                        }
                    }
                    when (val loadState = state.loadState) {
                        is DirectoryPickerLoadState.Loading -> item(key = "loading") {
                            Text(
                                text = "Loading directories…",
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is DirectoryPickerLoadState.Failed -> item(key = "failure") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                            ) {
                                Text(
                                    text = "Unable to read directory",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = loadState.failure.desktopMessage(),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        is DirectoryPickerLoadState.Ready -> {
                            if (state.visibleChildren.isEmpty()) {
                                item(key = "empty") {
                                    Text(
                                        text = if (state.filterQuery.isEmpty()) {
                                            "No child directories"
                                        } else {
                                            "No matching directories"
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                items(state.visibleChildren, key = Path::toString) { child ->
                                    DirectoryRow(
                                        label = "${child.name.ifEmpty { child.toString() }}/",
                                        onClick = { viewModel.navigateTo(child) },
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RectangleShape,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        TextButton(onClick = onDismissRequest) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryRow(
    label: String,
    onClick: () -> Unit,
): Unit {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RectangleShape,
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DirectoryPickerFailure.desktopMessage(): String = when (this) {
    DirectoryPickerFailure.HomeDirectoryUnavailable ->
        "Cannot resolve ~ because the user home directory was not found."

    is DirectoryPickerFailure.NotDirectory -> "Not a directory: $directory"
    is DirectoryPickerFailure.FileSystem -> detail
}
