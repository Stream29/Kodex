package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCatalogContextMenuTest {
    @Test
    fun secondaryClickOpensArchiveMenuAndRoutesArchive() = runTest {
        val result = selectSessionCatalogMenu(
            entry = SessionCatalogEntry(sessionIndex = 4, threadName = "Active"),
            moveDown = false,
        ) {
            sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Release))
        }

        assertTrue("[Archive" in result.menuSnapshot, result.menuSnapshot)
        assertTrue("Delete" in result.menuSnapshot, result.menuSnapshot)
        assertFalse("[Unarchive" in result.menuSnapshot, result.menuSnapshot)
        assertTrue("selection=archive" in result.selectionSnapshot, result.selectionSnapshot)
    }

    @Test
    fun shiftF10OpensUnarchiveMenuAndRoutesUnarchive() = runTest {
        val result = selectSessionCatalogMenu(
            entry = SessionCatalogEntry(
                sessionIndex = 5,
                threadName = "Archived",
                archived = true,
            ),
            moveDown = false,
        ) {
            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.F10,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
        }

        assertTrue("[Unarchive" in result.menuSnapshot, result.menuSnapshot)
        assertTrue("Delete" in result.menuSnapshot, result.menuSnapshot)
        assertFalse("[Archive" in result.menuSnapshot, result.menuSnapshot)
        assertTrue("selection=unarchive" in result.selectionSnapshot, result.selectionSnapshot)
    }

    @Test
    fun menuKeyRoutesDeleteThroughContextMenu() = runTest {
        val result = selectSessionCatalogMenu(
            entry = SessionCatalogEntry(sessionIndex = 6, threadName = "Delete target"),
            moveDown = true,
        ) {
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Menu))
        }

        assertTrue("[Archive" in result.menuSnapshot, result.menuSnapshot)
        assertTrue("Delete" in result.menuSnapshot, result.menuSnapshot)
        assertTrue("selection=delete" in result.selectionSnapshot, result.selectionSnapshot)
    }
}

private suspend fun selectSessionCatalogMenu(
    entry: SessionCatalogEntry,
    moveDown: Boolean,
    openMenu: suspend TestMosaic<String>.() -> Unit,
): SessionCatalogMenuSelection {
    var menuAnchor by mutableStateOf<TuiPopupAnchor?>(null)
    var menuClickPosition by mutableStateOf<IntOffset?>(null)
    var selection by mutableStateOf("none")
    var menuSnapshot = ""
    var selectionSnapshot = ""

    runMosaicTest {
        setContentAndSnapshot {
            TuiPopupHost(modifier = Modifier.width(48).height(6)) {
                Column(modifier = Modifier.width(48).height(6)) {
                    SessionCatalogRow(
                        entry = entry,
                        maximumLabelColumns = 46,
                        onClick = { selection = "open" },
                        onOpenContextMenu = { anchor, clickPosition ->
                            menuAnchor = anchor
                            menuClickPosition = clickPosition
                        },
                    )
                    Text("selection=$selection")
                }
                menuAnchor?.let { anchor ->
                    SessionCatalogContextMenuPopup(
                        entry = entry,
                        anchor = anchor,
                        clickPosition = menuClickPosition,
                        onDismissRequest = { menuAnchor = null },
                        onFork = {
                            menuAnchor = null
                            selection = "fork"
                        },
                        onArchive = {
                            menuAnchor = null
                            selection = "archive"
                        },
                        onUnarchive = {
                            menuAnchor = null
                            selection = "unarchive"
                        },
                        onDelete = {
                            menuAnchor = null
                            selection = "delete"
                        },
                    )
                }
            }
        }

        openMenu()
        menuSnapshot = awaitSnapshotContaining(if (entry.archived) "Unarchive" else "Archive")
        assertTrue("Index: ${entry.sessionIndex}" in menuSnapshot, menuSnapshot)
        if (moveDown) {
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            awaitSnapshot()
        }
        sendKeyEvent(KeyboardEvent(codepoint = 13))
        val expectedSelection = when {
            moveDown -> "delete"
            entry.archived -> "unarchive"
            else -> "archive"
        }
        selectionSnapshot = awaitSnapshotContaining("selection=$expectedSelection")
    }

    return SessionCatalogMenuSelection(
        menuSnapshot = menuSnapshot,
        selectionSnapshot = selectionSnapshot,
    )
}

private data class SessionCatalogMenuSelection(
    val menuSnapshot: String,
    val selectionSnapshot: String,
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
