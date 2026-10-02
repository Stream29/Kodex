package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.app.mcpsettings.McpDialogToken
import io.github.stream29.kodex.app.mcpsettings.McpEditorDraft
import io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog
import kotlinx.coroutines.TimeoutCancellationException
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val mcpSettingsDialogsTest by testSuite {
    test("editorUsesDropdownForTransportAndCheckboxForOAuth") {
        var editor by mutableStateOf(McpSettingsDialog.Editing(
            McpDialogToken(), null, McpEditorDraft(),
        ))
        runMosaicTest {
            val initial = setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(100).height(30)) {
                    McpServerEditorDialog(
                        editor = editor,
                        onDraftChange = { editor = editor.copy(draft = it) },
                        onDismiss = {},
                        onSave = {},
                    )
                }
            }

            assertTrue("Transport [HTTP]" in initial, initial)
            assertTrue("[ ] OAuth" in initial, initial)
            assertFalse("[HTTP] [stdio]" in initial, initial)
            assertFalse("OAuth [" in initial, initial)

            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[stdio]")
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val stdio = awaitSnapshotContaining("Transport [stdio]")

            assertTrue("Command" in stdio, stdio)
            assertFalse("OAuth [" in stdio, stdio)
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
