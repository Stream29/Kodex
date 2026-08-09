package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberLazyListState
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

/**
 * Modal directory browser that reports an explicitly confirmed, absolute directory to its caller.
 *
 * It exposes no caller-specific persistence behavior. Cancelling and outside dismissal invoke
 * [onDismissRequest]; Escape clears an active filter before invoking it.
 */
@Composable
public fun BoxScope.DirectoryPickerPopup(
    initialDirectory: Path,
    onDismissRequest: () -> Unit,
    onDirectorySelected: (Path) -> Unit,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    val terminal = LocalTerminalState.current
    val browser = remember(fileSystem) { DirectoryPickerBrowser(fileSystem) }
    var requestedDirectory by remember(initialDirectory) { mutableStateOf(initialDirectory) }
    var loadState by remember(initialDirectory) { mutableStateOf<DirectoryPickerLoadState>(Loading) }
    var filterQuery by remember(initialDirectory) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(browser, requestedDirectory) {
        loadState = Loading
        loadState = try {
            Ready(browser.load(requestedDirectory))
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            Failure(failure.message ?: failure.toString())
        }
    }

    val currentLoadState = loadState
    val listing = (currentLoadState as? Ready)?.listing
    val filteredChildren = listing?.children.orEmpty().let { children ->
        if (filterQuery.isEmpty()) {
            children
        } else {
            children.filter { child ->
                directoryName(child).contains(filterQuery, ignoreCase = true)
            }
        }
    }
    val firstFilteredChild = filteredChildren.firstOrNull()
    val firstFilteredChildFocusRequester = remember(firstFilteredChild) { FocusRequester() }
    val currentDirectory = listing?.directory ?: requestedDirectory
    val parentDirectory = currentDirectory.parent
    val canNavigateUp = parentDirectory != null && currentLoadState !is Loading
    fun navigateTo(directory: Path) {
        filterQuery = ""
        requestedDirectory = directory
    }

    val navigateUp: () -> Unit = {
        if (canNavigateUp) parentDirectory.let(::navigateTo)
    }
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
                filterQuery = ""
            }
        },
        modifier = Modifier
            .width(dialogWidth)
            .background(DirectoryPickerBackground)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == BackspaceKey && filterQuery.isNotEmpty() && !event.ctrl && !event.alt -> {
                        filterQuery = filterQuery.dropLast(1)
                        true
                    }

                    event.isFilterLetter() -> {
                        filterQuery += event.key
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
                    navigateUp()
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
                textStyle = TextStyle.Bold,
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
                    label = "Up",
                    color = DirectoryPickerForeground,
                    enabled = canNavigateUp,
                    onClick = navigateUp,
                )
                Text(" ")
                TuiButton(
                    label = "Select",
                    color = DirectoryPickerForeground,
                    enabled = listing != null,
                    onClick = { listing?.let { onDirectorySelected(it.directory) } },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listRows)
                    .background(DirectoryPickerListBackground),
            ) {
                when (currentLoadState) {
                    Loading -> Text("Loading directories…", color = DirectoryPickerForeground)
                    is Failure -> DirectoryPickerFailure(currentLoadState.message, dialogWidth)
                    is Ready -> if (filteredChildren.isEmpty()) {
                        Text(
                            if (filterQuery.isEmpty()) "No child directories" else "No matching directories",
                            color = DirectoryPickerForeground,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().height(listRows),
                        ) {
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
                                    onClick = { navigateTo(child) },
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
private fun DirectoryPickerFailure(message: String, width: Int) {
    Column(modifier = Modifier.fillMaxWidth().background(DirectoryPickerListBackground)) {
        Text("Unable to read directory", color = DirectoryPickerForeground)
        Text(
            value = message.ellipsizeToTerminalWidth(width),
            color = DirectoryPickerForeground,
        )
    }
}

private fun KeyEvent.isFilterLetter(): Boolean =
    !ctrl && !alt && key.length == 1 && key.single().isLetter()

private fun directoryName(path: Path): String = path.name.ifEmpty { path.toString() }

private fun directoryLabel(path: Path): String = "${directoryName(path)}/"

private sealed interface DirectoryPickerLoadState

private data object Loading : DirectoryPickerLoadState

private data class Ready(val listing: DirectoryPickerListing) : DirectoryPickerLoadState

private data class Failure(val message: String) : DirectoryPickerLoadState

private val DirectoryPickerForeground: Color = Color.White
private val DirectoryPickerBackground: Color = Color(52, 52, 56)
private val DirectoryPickerHeaderBackground: Color = Color(28, 68, 74)
private val DirectoryPickerCurrentPathBackground: Color = Color(42, 42, 46)
private val DirectoryPickerListBackground: Color = Color(52, 52, 56)
private val DirectoryPickerActionBackground: Color = Color(28, 68, 74)

private const val DirectoryPickerMaximumWidth: Int = 84
private const val DirectoryPickerMaximumListRows: Int = 16
private const val DirectoryPickerReservedRows: Int = 9
private const val DirectoryPickerButtonBorders: Int = 2
private const val BackspaceKey: String = "Backspace"
private const val FilterPlaceholder: String = "type letters"
