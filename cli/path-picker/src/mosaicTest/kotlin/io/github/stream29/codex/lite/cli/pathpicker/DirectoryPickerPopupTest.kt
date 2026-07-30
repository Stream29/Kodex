package io.github.stream29.codex.lite.cli.pathpicker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.cli.components.TuiPopupHost
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.TimeoutCancellationException
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
        try {
            SystemCoroutineFileSystem.createDirectories(Path(directory, "child"))
            SystemCoroutineFileSystem.writeString(Path(directory, "file.txt"), "not selectable")
            var expanded by mutableStateOf(true)
            var selected: Path? = null

            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                            if (expanded) {
                                DirectoryPickerPopup(
                                    initialDirectory = directory,
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
                sendMouseEvent(MouseEvent(8, 4, MouseEvent.Type.Press, MouseEvent.Button.Left))
                awaitSnapshot()
                sendMouseEvent(MouseEvent(8, 4, MouseEvent.Type.Release))
                awaitSnapshot()
            }

            assertEquals(SystemCoroutineFileSystem.resolve(directory), selected)
            assertFalse(expanded)
        } finally {
            deleteRecursively(root)
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(3) {
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

private fun temporaryDirectory(name: String): Path =
    Path(SystemTemporaryDirectory, "codex-lite-$name-${Random.nextLong()}")

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
