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
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarCallbacks
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.cli.sessiontabbar.SessionTabBar as ComponentSessionTabBar
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import kotlinx.coroutines.TimeoutCancellationException
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

val sessionTabContextMenuTest by testSuite {
    test("persistedTabOffersAndRoutesCloseAndArchive") {
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
                            createdAt = "2026-09-08 09:02:03 UTC+08:00",
                            updatedAt = "2026-09-08 10:02:03 UTC+08:00",
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
            assertTrue("Index: ${target.sessionIndex}" in menuSnapshot, menuSnapshot)
            assertTrue("Created at: 2026-09-08 09:02:03 UTC+08:00" in menuSnapshot, menuSnapshot)
            assertTrue("Updated at: 2026-09-08 10:02:03 UTC+08:00" in menuSnapshot, menuSnapshot)
            assertTrue("Rename" in menuSnapshot, menuSnapshot)
            assertTrue("Close" in menuSnapshot, menuSnapshot)
            assertTrue("Close and archive" in menuSnapshot, menuSnapshot)
            assertTrue("Delete" in menuSnapshot, menuSnapshot)
        } finally {
            fixture.close()
        }
    }

    test("singletonPlusHasNoOrdinaryTabContextMenu") {
        var selections by mutableStateOf(0)
        var menus = 0
        var closes = 0
        val callbacks = object : SessionTabBarCallbacks {
            override fun select(identity: SessionTabIdentity) = Unit
            override fun close(identity: SessionTabIdentity) { closes++ }
            override fun openContextMenu(identity: SessionTabIdentity) { menus++ }
            override fun selectNewSession() { selections++ }
            override fun openSessions() = Unit
        }
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(48)) {
                    ComponentSessionTabBar(
                        state = SessionTabBarState(emptyList(), null),
                        callbacks = callbacks,
                        runningIndicatorFrame = mutableStateOf(""),
                        columns = 48,
                        onOpenTabMenu = { menus++ },
                    )
                    Text("selections=$selections")
                }
            }
            assertTrue("[+]" in snapshot, snapshot)
            sendMouseEvent(MouseEvent(46, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(46, 0, MouseEvent.Type.Release))
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.F10, modifiers = KeyboardEvent.ModifierShift))
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Menu))
            sendMouseEvent(MouseEvent(46, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(46, 0, MouseEvent.Type.Release))
            val latest = awaitSnapshot()
            assertFalse("Rename" in latest || "Close and archive" in latest, latest)
        }
        assertEquals(0, menus)
        assertEquals(0, closes)
        assertEquals(1, selections)
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
