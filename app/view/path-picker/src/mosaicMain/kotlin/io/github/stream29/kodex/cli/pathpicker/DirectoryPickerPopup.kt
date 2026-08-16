package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.layout.onPreviewPointerEvent
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.canConfirm
import io.github.stream29.kodex.app.pathpicker.contract.canNavigateUp
import io.github.stream29.kodex.app.pathpicker.contract.currentDirectory
import io.github.stream29.kodex.app.pathpicker.contract.visibleChildren
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberLazyListState
import kotlinx.io.files.Path

/**
 * Modal directory browser that reports an explicitly confirmed, absolute directory to its caller.
 *
 * It exposes no caller-specific persistence behavior. Cancelling and outside dismissal invoke
 * [onDismissRequest]; Escape clears an active filter before invoking it.
 */
@Composable
public fun BoxScope.DirectoryPickerPopup(
    viewModel: DirectoryPickerViewModel,
    onDismissRequest: () -> Unit,
    onDirectorySelected: (Path) -> Unit,
) {
    val terminal = LocalTerminalState.current
    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    val state by viewModel.state.collectAsState()
    val currentOnDirectorySelected by rememberUpdatedState(onDirectorySelected)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DirectoryPickerEffect.DirectorySelected ->
                    currentOnDirectorySelected(effect.directory)
            }
        }
    }
    val listState = rememberLazyListState()

    val currentLoadState = state.loadState
    val listing = currentLoadState as? DirectoryPickerLoadState.Ready
    val filterQuery = state.filterQuery
    val filteredChildren = state.visibleChildren
    val firstFilteredChild = filteredChildren.firstOrNull()
    val firstFilteredChildFocusRequester = remember(firstFilteredChild) { FocusRequester() }
    val currentDirectory = state.currentDirectory
    LaunchedEffect(listing?.directory) {
        listState.requestScrollToStart()
    }
    LaunchedEffect(filterQuery, firstFilteredChild) {
        if (filterQuery.isEmpty()) return@LaunchedEffect
        listState.requestScrollToStart()
        if (firstFilteredChild == null) return@LaunchedEffect
        withFrameNanos { }
        firstFilteredChildFocusRequester.requestFocus()
    }

    val dialogWidth = (terminal.size.columns - 4).coerceIn(1, DirectoryPickerMaximumWidth)
    val listRows = (terminal.size.rows - DirectoryPickerReservedRows)
        .coerceAtLeast(1)
        .coerceAtMost(DirectoryPickerMaximumListRows)

    TuiDialog(
        onDismissRequest = onDismissRequest,
        onEscapeRequest = {
            if (filterQuery.isEmpty()) {
                onDismissRequest()
            } else {
                viewModel.clearFilter()
            }
        },
        modifier = Modifier
            .width(dialogWidth)
            .background(DirectoryPickerBackground)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == BackspaceKey && filterQuery.isNotEmpty() && !event.ctrl && !event.alt -> {
                        viewModel.updateFilter(filterQuery.dropLast(1))
                        true
                    }

                    event.isFilterLetter() -> {
                        viewModel.updateFilter(filterQuery + event.key)
                        true
                    }

                    else -> false
                }
            }
            .onPreviewPointerEvent { event ->
                if (
                    event.type == MouseEvent.Type.Press &&
                    event.button == MouseEvent.Button.Button8
                ) {
                    viewModel.navigateUp()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(DirectoryPickerBackground)) {
            Text(
                value = "Select directory",
                modifier = Modifier.fillMaxWidth().background(DirectoryPickerHeaderBackground),
                color = DirectoryPickerForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text(
                value = currentDirectory.toString().ellipsizeToTerminalWidth(dialogWidth),
                modifier = Modifier.fillMaxWidth().background(DirectoryPickerCurrentPathBackground),
                color = DirectoryPickerForeground,
            )
            Text(
                value = "Filter: ${filterQuery.ifEmpty { FilterPlaceholder }}"
                    .ellipsizeToTerminalWidth(dialogWidth),
                modifier = Modifier.fillMaxWidth().background(DirectoryPickerCurrentPathBackground),
                color = DirectoryPickerForeground,
            )
            Row(modifier = Modifier.fillMaxWidth().background(DirectoryPickerActionBackground)) {
                TuiButton(
                    label = "Select",
                    color = DirectoryPickerForeground,
                    enabled = state.canConfirm,
                    onClick = viewModel::confirm,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listRows)
                    .background(DirectoryPickerListBackground),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(listRows),
                ) {
                    if (state.canNavigateUp) {
                        item {
                            TuiButton(
                                label = ParentDirectoryLabel,
                                modifier = Modifier.fillMaxWidth().background(DirectoryPickerListBackground),
                                color = DirectoryPickerForeground,
                                onClick = viewModel::navigateUp,
                            )
                        }
                    }
                    when (currentLoadState) {
                        is DirectoryPickerLoadState.Loading -> item {
                            Text("Loading directories…", color = DirectoryPickerForeground)
                        }

                        is DirectoryPickerLoadState.Failed -> item {
                            DirectoryPickerFailureView(currentLoadState.failure, dialogWidth)
                        }

                        is DirectoryPickerLoadState.Ready -> if (filteredChildren.isEmpty()) {
                            item {
                                Text(
                                    if (filterQuery.isEmpty()) {
                                        "No child directories"
                                    } else {
                                        "No matching directories"
                                    },
                                    color = DirectoryPickerForeground,
                                )
                            }
                        } else {
                            items(filteredChildren, key = Path::toString) { child ->
                                TuiButton(
                                    label = directoryLabel(child).ellipsizeToTerminalWidth(
                                        (dialogWidth - DirectoryPickerButtonBorders).coerceAtLeast(1),
                                    ),
                                    modifier = Modifier.fillMaxWidth().background(DirectoryPickerListBackground),
                                    color = DirectoryPickerForeground,
                                    focusRequester = if (
                                        filterQuery.isNotEmpty() && child == firstFilteredChild
                                    ) {
                                        firstFilteredChildFocusRequester
                                    } else {
                                        null
                                    },
                                    onClick = { viewModel.navigateTo(child) },
                                )
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().background(DirectoryPickerActionBackground)) {
                TuiButton(
                    label = "Cancel",
                    color = DirectoryPickerForeground,
                    onClick = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun DirectoryPickerFailureView(failure: DirectoryPickerFailure, width: Int) {
    Column(modifier = Modifier.fillMaxWidth().background(DirectoryPickerListBackground)) {
        Text("Unable to read directory", color = DirectoryPickerForeground)
        Text(
            value = failure.message().ellipsizeToTerminalWidth(width),
            color = DirectoryPickerForeground,
        )
    }
}

private fun DirectoryPickerFailure.message(): String =
    when (this) {
        DirectoryPickerFailure.HomeDirectoryUnavailable ->
            "Cannot resolve ~ because the user home directory was not found."

        is DirectoryPickerFailure.NotDirectory -> "Not a directory: $directory"
        is DirectoryPickerFailure.FileSystem -> detail
    }

private fun KeyEvent.isFilterLetter(): Boolean =
    !ctrl && !alt && key.length == 1 && key.single().isLetter()

private fun directoryName(path: Path): String = path.name.ifEmpty { path.toString() }

private fun directoryLabel(path: Path): String = "${directoryName(path)}/"

private val DirectoryPickerForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

private val DirectoryPickerBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

private val DirectoryPickerHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

private val DirectoryPickerCurrentPathBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

private val DirectoryPickerListBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

private val DirectoryPickerActionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

private const val DirectoryPickerMaximumWidth: Int = 84
private const val DirectoryPickerMaximumListRows: Int = 16
private const val DirectoryPickerReservedRows: Int = 9
private const val DirectoryPickerButtonBorders: Int = 2
private const val BackspaceKey: String = "Backspace"
private const val FilterPlaceholder: String = "type letters"
private const val ParentDirectoryLabel: String = ".."
