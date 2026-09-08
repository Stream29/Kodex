package io.github.stream29.kodex.cli.app

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
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import kotlinx.coroutines.TimeoutCancellationException
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val historyEntryContextMenuTest by testSuite {
    test("menuUsesDirectionalNamesAndRoutesRevert") {
        val result = selectHistoryEntryMenuItem(moveDown = 0)

        assertTrue("[Revert to here]" in result.snapshot, result.snapshot)
        assertTrue("[Fork from here]" in result.snapshot, result.snapshot)
        assertFalse("through here" in result.snapshot, result.snapshot)
        assertFalse("Revert and edit" in result.snapshot, result.snapshot)
        assertEquals("revert", result.selection)
    }

    test("menuRoutesForkFromTheSelectedEntry") {
        val result = selectHistoryEntryMenuItem(moveDown = 1)

        assertEquals("fork", result.selection)
    }

    test("editableUserMenuAddsThirdActionWithoutMovingRevertOrFork") {
        val result = selectHistoryEntryMenuItem(moveDown = 2, editable = true)
        assertEquals("edit", result.selection)
        assertTrue(result.snapshot.indexOf("Revert to here") < result.snapshot.indexOf("Fork from here"))
        assertTrue(result.snapshot.indexOf("Fork from here") < result.snapshot.indexOf("Revert and edit"))
    }

}

private suspend fun selectHistoryEntryMenuItem(
    moveDown: Int,
    editable: Boolean = false,
): HistoryEntryMenuSelection {
    var selection by mutableStateOf("none")
    var menuSnapshot = ""

    runMosaicTest {
        setContentAndSnapshot {
            val anchor = rememberTuiPopupAnchor()
            TuiPopupHost(modifier = Modifier.width(48).height(6)) {
                Column(modifier = Modifier.width(48).height(6)) {
                    Text("history entry", modifier = Modifier.tuiPopupAnchor(anchor))
                    Text("")
                    Text("")
                    Text("")
                    Text("selection=$selection")
                }
                HistoryEntryContextMenuPopup(
                    anchor = anchor,
                    clickPosition = null,
                    onDismiss = {},
                    onRevert = { selection = "revert" },
                    onFork = { selection = "fork" },
                    onRevertAndEdit = if (editable) ({ selection = "edit" }) else null,
                )
            }
        }
        menuSnapshot = awaitSnapshotContaining("Fork from here")
        repeat(moveDown) {
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            awaitSnapshot()
        }
        sendKeyEvent(KeyboardEvent(codepoint = 13))
        awaitSnapshotContaining("selection=${listOf("revert", "fork", "edit")[moveDown]}")
    }

    return HistoryEntryMenuSelection(menuSnapshot, selection)
}

private data class HistoryEntryMenuSelection(
    val snapshot: String,
    val selection: String,
)

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
