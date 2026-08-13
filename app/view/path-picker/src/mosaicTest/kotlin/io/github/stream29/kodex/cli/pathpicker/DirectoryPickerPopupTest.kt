package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val directoryPickerPopupTest by testSuite {
    test("renders child directories and confirms the current resolved directory") {
        val unresolvedRoot = temporaryDirectory("directory-picker-popup")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val directory = Path(root, "workspace")
        val viewModels = DirectoryPickerViewModels()
        try {
            SystemCoroutineFileSystem.createDirectories(Path(directory, "child"))
            SystemCoroutineFileSystem.writeString(Path(directory, "file.txt"), "not selectable")
            val viewModel = viewModels.create(directory)
            var expanded by mutableStateOf(true)
            var selected: Path? = null

            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                            if (expanded) {
                                DirectoryPickerPopup(
                                    viewModel = viewModel,
                                    onDismissRequest = { expanded = false },
                                    onDirectorySelected = { path ->
                                        selected = path
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                val snapshot = awaitSnapshotContaining("[child/]")
                assertTrue("file.txt" !in snapshot, snapshot)
                assertTrue("[Up]" !in snapshot, snapshot)
                assertTrue("[Select]" in snapshot, snapshot)
                assertTrue("[..]" in snapshot, snapshot)
                assertTrue(snapshot.indexOf("[Select]") < snapshot.indexOf("[..]"), snapshot)
                assertTrue(snapshot.indexOf("[..]") < snapshot.indexOf("[child/]"), snapshot)
                sendMouseEvent(MouseEvent(8, 5, MouseEvent.Type.Press, MouseEvent.Button.Left))
                awaitSnapshot()
                sendMouseEvent(MouseEvent(8, 5, MouseEvent.Type.Release))
                awaitSnapshotUntil { selected != null }
            }

            assertEquals(SystemCoroutineFileSystem.resolve(directory), selected)
            assertFalse(expanded)
        } finally {
            viewModels.close()
            deleteRecursively(root)
        }
    }

    test("mouse button 8 navigates to the parent and clears the filter") {
        val unresolvedRoot = temporaryDirectory("directory-picker-button-8")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val directory = Path(root, "workspace")
        val viewModels = DirectoryPickerViewModels()
        try {
            SystemCoroutineFileSystem.createDirectories(Path(directory, "needle"))
            SystemCoroutineFileSystem.createDirectories(Path(root, "sibling"))
            val viewModel = viewModels.create(directory)

            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                            DirectoryPickerPopup(
                                viewModel = viewModel,
                                onDismissRequest = {},
                                onDirectorySelected = {},
                            )
                        }
                    }
                }

                awaitSnapshotContaining("[needle/]")
                sendKeyEvent(KeyboardEvent(codepoint = 'n'.code))
                awaitSnapshotContaining("Filter: n")
                sendMouseEvent(
                    MouseEvent(
                        x = 40,
                        y = 12,
                        type = MouseEvent.Type.Press,
                        button = MouseEvent.Button.Button8,
                    )
                )
                val parent = awaitSnapshotContaining("[workspace/]")
                assertTrue("[sibling/]" in parent, parent)
                assertTrue("Filter: type letters" in parent, parent)
            }
        } finally {
            viewModels.close()
            deleteRecursively(root)
        }
    }

    test("typing filters case-insensitively and enter opens the first match") {
        val unresolvedRoot = temporaryDirectory("directory-picker-filter")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val viewModels = DirectoryPickerViewModels()
        try {
            SystemCoroutineFileSystem.createDirectories(Path(root, "alpha", "inside-alpha"))
            SystemCoroutineFileSystem.createDirectories(Path(root, "beta", "inside-beta"))
            SystemCoroutineFileSystem.createDirectories(Path(root, "zulu"))
            val viewModel = viewModels.create(root)

            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                            DirectoryPickerPopup(
                                viewModel = viewModel,
                                onDismissRequest = {},
                                onDirectorySelected = {},
                            )
                        }
                    }
                }

                awaitSnapshotContaining("[zulu/]")
                sendKeyEvent(
                    KeyboardEvent(
                        codepoint = 'A'.code,
                        modifiers = KeyboardEvent.ModifierShift,
                    )
                )
                val filtered = awaitSnapshotContaining("Filter: A")
                assertTrue("[alpha/]" in filtered, filtered)
                assertTrue("[beta/]" in filtered, filtered)
                assertTrue("[zulu/]" !in filtered, filtered)
                awaitSnapshot()

                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val opened = awaitSnapshotContaining("[inside-alpha/]")
                assertTrue("[inside-beta/]" !in opened, opened)
                assertTrue("Filter: type letters" in opened, opened)
            }
        } finally {
            viewModels.close()
            deleteRecursively(root)
        }
    }

    test("backspace edits the filter and escape clears it before dismissing") {
        val unresolvedRoot = temporaryDirectory("directory-picker-filter-escape")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val viewModels = DirectoryPickerViewModels()
        try {
            SystemCoroutineFileSystem.createDirectories(Path(root, "alpha"))
            SystemCoroutineFileSystem.createDirectories(Path(root, "beta"))
            val viewModel = viewModels.create(root)
            var expanded by mutableStateOf(true)

            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                            if (expanded) {
                                DirectoryPickerPopup(
                                    viewModel = viewModel,
                                    onDismissRequest = { expanded = false },
                                    onDirectorySelected = {},
                                )
                            }
                        }
                    }
                }

                awaitSnapshotContaining("[beta/]")
                sendKeyEvent(KeyboardEvent(codepoint = 'z'.code))
                awaitSnapshotContaining("No matching directories")
                sendKeyEvent(KeyboardEvent(codepoint = 127))
                val restored = awaitSnapshotContaining("[alpha/]")
                assertTrue("Filter: type letters" in restored, restored)

                sendKeyEvent(KeyboardEvent(codepoint = 'b'.code))
                awaitSnapshotContaining("Filter: b")
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                val cleared = awaitSnapshotContaining("[alpha/]")
                assertTrue(expanded)
                assertTrue("Filter: type letters" in cleared, cleared)

                sendKeyEvent(KeyboardEvent(codepoint = 27))
                awaitSnapshot()
            }

            assertFalse(expanded)
        } finally {
            viewModels.close()
            deleteRecursively(root)
        }
    }
}

private class DirectoryPickerViewModels {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun create(initialDirectory: Path): DirectoryPickerViewModel =
        createDirectoryPickerViewModel(initialDirectory, scope)

    fun close() {
        scope.cancel()
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(10) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            return@repeat
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}

private suspend fun TestMosaic<String>.awaitSnapshotUntil(predicate: () -> Boolean): String {
    var latest = ""
    repeat(10) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            return@repeat
        }
        if (predicate()) return latest
    }
    assertTrue(predicate(), latest)
    return latest
}

private fun temporaryDirectory(name: String): Path =
    Path(SystemTemporaryDirectory, "kodex-$name-${Random.nextLong()}")

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
