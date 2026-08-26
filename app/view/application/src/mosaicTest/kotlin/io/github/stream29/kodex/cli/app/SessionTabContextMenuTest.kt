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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionTabContextMenuTest {
    @Test
    fun persistedTabOffersAndRoutesCloseAndArchive() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.persistedSession("Persisted")
            var selection by mutableStateOf("none")
            var menuSnapshot = ""

            runMosaicTest {
                setContentAndSnapshot {
                    val anchor = rememberTuiPopupAnchor()
                    TuiPopupHost(modifier = Modifier.width(48).height(8)) {
                        Column(modifier = Modifier.width(48).height(8)) {
                            Text("session tab", modifier = Modifier.tuiPopupAnchor(anchor))
                            Text("selection=$selection")
                        }
                        SessionTabContextMenuPopup(
                            target = target,
                            anchor = anchor,
                            clickPosition = null,
                            onDismiss = {},
                            onClose = { selection = "close" },
                            onCloseAndArchive = { selected ->
                                assertSame(target, selected)
                                selection = "close-and-archive"
                            },
                            onRename = { selection = "rename" },
                            onDelete = { selection = "delete" },
                        )
                    }
                }

                menuSnapshot = awaitSnapshotContaining("Close and archive")
                repeat(2) {
                    sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
                    awaitSnapshot()
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
            }

            assertEquals("close-and-archive", selection)
            assertTrue("Rename" in menuSnapshot, menuSnapshot)
            assertTrue("Close" in menuSnapshot, menuSnapshot)
            assertTrue("Close and archive" in menuSnapshot, menuSnapshot)
            assertTrue("Delete" in menuSnapshot, menuSnapshot)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun draftTabDoesNotOfferPersistedActions() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.newSession("Draft")
            var menuSnapshot = ""

            runMosaicTest {
                setContentAndSnapshot {
                    val anchor = rememberTuiPopupAnchor()
                    TuiPopupHost(modifier = Modifier.width(48).height(6)) {
                        Text("session tab", modifier = Modifier.tuiPopupAnchor(anchor))
                        SessionTabContextMenuPopup(
                            target = target,
                            anchor = anchor,
                            clickPosition = null,
                            onDismiss = {},
                            onClose = {},
                            onCloseAndArchive = {},
                            onRename = {},
                            onDelete = {},
                        )
                    }
                }

                menuSnapshot = awaitSnapshotContaining("Close")
            }

            assertTrue("Rename" in menuSnapshot, menuSnapshot)
            assertTrue("Close" in menuSnapshot, menuSnapshot)
            assertFalse("Close and archive" in menuSnapshot, menuSnapshot)
            assertFalse("Delete" in menuSnapshot, menuSnapshot)
        } finally {
            fixture.close()
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
