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
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntry
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntryKey
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowSnapshot
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryEntryContextMenuTest {
    @Test
    fun menuUsesDirectionalNamesAndRoutesRevert() = runTest {
        val result = selectHistoryEntryMenuItem(moveDown = false)

        assertTrue("[Revert to here]" in result.snapshot, result.snapshot)
        assertTrue("[Fork from here]" in result.snapshot, result.snapshot)
        assertFalse("through here" in result.snapshot, result.snapshot)
        assertEquals("revert", result.selection)
    }

    @Test
    fun menuRoutesForkFromTheSelectedEntry() = runTest {
        val result = selectHistoryEntryMenuItem(moveDown = true)

        assertEquals("fork", result.selection)
    }

    @Test
    fun historyTargetRequiresTheSameGenerationAndAnEntryStillInTheWindow() {
        val window = AgentHistoryWindowSnapshot(
            generation = 4,
            entries = listOf(historyEntry()),
        )

        assertTrue(AgentHistoryTarget(generation = 4, storageIndex = 17).isCurrentIn(window))
        assertFalse(AgentHistoryTarget(generation = 3, storageIndex = 17).isCurrentIn(window))
        assertFalse(AgentHistoryTarget(generation = 4, storageIndex = 16).isCurrentIn(window))
    }
}

private suspend fun selectHistoryEntryMenuItem(
    moveDown: Boolean,
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
                )
            }
        }
        menuSnapshot = awaitSnapshotContaining("Fork from here")
        if (moveDown) {
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            awaitSnapshot()
        }
        sendKeyEvent(KeyboardEvent(codepoint = 13))
        awaitSnapshotContaining("selection=${if (moveDown) "fork" else "revert"}")
    }

    return HistoryEntryMenuSelection(menuSnapshot, selection)
}

private fun historyEntry(): AgentHistoryEntry = AgentHistoryEntry(
    key = AgentHistoryEntryKey(primaryStorageIndex = 17),
    event = StableCleanEvent.UserMessage(
        listOf(ContentItem.InputText("entry-17")),
    ),
)

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
