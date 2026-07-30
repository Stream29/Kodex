package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
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
 * It exposes no caller-specific persistence behavior. Cancelling, Escape, and outside dismissal
 * only invoke [onDismissRequest].
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
    val currentDirectory = listing?.directory ?: requestedDirectory
    val parentDirectory = currentDirectory.parent
    LaunchedEffect(listing?.directory) {
        listState.requestScrollToStart()
    }

    val dialogWidth = (terminal.size.columns - 4).coerceIn(1, DirectoryPickerMaximumWidth)
    val listRows = (terminal.size.rows - DirectoryPickerReservedRows)
        .coerceAtLeast(1)
        .coerceAtMost(DirectoryPickerMaximumListRows)

    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(dialogWidth).background(DirectoryPickerBackground),
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
            Row(modifier = Modifier.fillMaxWidth().background(DirectoryPickerActionBackground)) {
                TuiButton(
                    label = "Up",
                    color = DirectoryPickerForeground,
                    enabled = parentDirectory != null && currentLoadState !is Loading,
                    onClick = { parentDirectory?.let { requestedDirectory = it } },
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
                    is Ready -> if (currentLoadState.listing.children.isEmpty()) {
                        Text("No child directories", color = DirectoryPickerForeground)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().height(listRows),
                        ) {
                            items(currentLoadState.listing.children, key = Path::toString) { child ->
                                TuiButton(
                                    label = directoryLabel(child).ellipsizeToTerminalWidth(
                                        (dialogWidth - DirectoryPickerButtonBorders).coerceAtLeast(1),
                                    ),
                                    modifier = Modifier.fillMaxWidth().background(DirectoryPickerListBackground),
                                    color = DirectoryPickerForeground,
                                    onClick = { requestedDirectory = child },
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

private fun directoryLabel(path: Path): String = "${path.name.ifEmpty { path.toString() }}/"

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
private const val DirectoryPickerReservedRows: Int = 8
private const val DirectoryPickerButtonBorders: Int = 2
