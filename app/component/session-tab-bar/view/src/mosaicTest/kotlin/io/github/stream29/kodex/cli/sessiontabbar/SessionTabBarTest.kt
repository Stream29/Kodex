package io.github.stream29.kodex.cli.sessiontabbar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarCallbacks
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabPresentation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val fixedRunningIndicatorFrame = mutableStateOf("⠋")

val sessionTabBarRendererTest by testSuite {
    test("plus and ordinary selected tab use mutually exclusive reverse video") {
        val identity = SessionTabIdentity("persisted")
        var selected by mutableStateOf<SessionTabIdentity?>(null)
        val snapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.ANSI16, supportsKittyUnderlines = false)
        }
        val callbacks = RecordingCallbacks()
        fun inverted(label: String) = Regex("\u001B\\[(?:[0-9]+;)*7m\\[${Regex.escape(label)}]")
        runMosaicTest(snapshotStrategy = snapshots) {
            val newSession = setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    SessionTabBar(
                        SessionTabBarState(listOf(SessionTabPresentation(identity, "Persisted")), selected),
                        callbacks, fixedRunningIndicatorFrame, 40,
                    )
                }
            }
            assertTrue(inverted("+").containsMatchIn(newSession), newSession)
            assertFalse(inverted("Persisted").containsMatchIn(newSession), newSession)
            selected = identity
            val persisted = awaitSnapshot()
            assertTrue(inverted("Persisted").containsMatchIn(persisted), persisted)
            assertFalse(inverted("+").containsMatchIn(persisted), persisted)
        }
    }

    test("empty tabs retain Sessions and plus pointer and keyboard actions") {
        var revision by mutableStateOf(0)
        val recording = RecordingCallbacks()
        val callbacks = object : SessionTabBarCallbacks by recording {
            override fun selectNewSession() { recording.selectNewSession(); revision++ }
            override fun openSessions() { recording.openSessions(); revision++ }
        }
        runMosaicTest {
            val initial = setContentAndSnapshot {
                Column(Modifier.width(32)) {
                    SessionTabBar(
                        SessionTabBarState(emptyList(), null),
                        callbacks, fixedRunningIndicatorFrame, 32,
                    )
                    Text("revision=$revision")
                }
            }
            assertTrue("[Sessions]" in initial && "[+]" in initial, initial)
            sendMouseEvent(MouseEvent(30, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(30, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            assertEquals(1, recording.newSessionCount)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(2, recording.newSessionCount)
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(1, recording.sessionsCount)
        }
        assertTrue(recording.selected.isEmpty() && recording.closed.isEmpty() && recording.menus.isEmpty())
    }

    for (width in listOf(1, 8, 14, 15, 16, 32)) {
        test("empty top bar clips to $width columns and can page to both controls") {
            val callbacks = RecordingCallbacks()
            runMosaicTest {
                val initial = setContentAndSnapshot {
                    Box(Modifier.width(width)) {
                        SessionTabBar(
                            SessionTabBarState(emptyList(), null),
                            callbacks, fixedRunningIndicatorFrame, width,
                        )
                    }
                }
                assertTrue(initial.lines().all { it.length <= width }, initial)
                // Empty tabs leave paging available to the outer controls viewport.
                val pages = if (width < 16) (16 - width + width - 1) / width else 0
                var end = initial
                repeat(pages) {
                    sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
                    end = awaitSnapshot()
                }
                assertTrue(end.lines().all { it.length <= width }, end)
                if (width >= 3) assertTrue("[+]" in end, end)
                var start = end
                repeat(pages) {
                    sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
                    start = awaitSnapshot()
                }
                assertTrue(start.startsWith("[Sessions]".take(width)), start)
            }
        }
    }

    test("ordering, running label truncation, selected bounds, and compact controls are preserved") {
        val state = SessionTabBarState(
            tabs = listOf(
                SessionTabPresentation(SessionTabIdentity("first"), "First"),
                SessionTabPresentation(
                    SessionTabIdentity("running"),
                    "abcdefghijklmnopqrstuv",
                    running = true,
                ),
            ),
            selected = SessionTabIdentity("running"),
        )
        val callbacks = RecordingCallbacks()

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box(Modifier.width(32)) {
                    SessionTabBar(
                        state = state,
                        callbacks = callbacks,
                        runningIndicatorFrame = fixedRunningIndicatorFrame,
                        columns = 32,
                    )
                }
            }
            assertTrue("[Sessions]" in snapshot && "[+]" in snapshot, snapshot)
            assertTrue("[First]" in snapshot, snapshot)
            assertTrue("[⠋" in snapshot, snapshot)
            assertFalse("abcdefghijklmnopqrstuv" in snapshot, snapshot)
            assertTrue(snapshot.indexOf("[First]") < snapshot.indexOf("[⠋"), snapshot)
            assertFalse("  " in snapshot, snapshot)
        }

        assertEquals(
            SessionTabBounds(start = 0, endExclusive = 5),
            sessionTabBounds(listOf("one"), 0),
        )
        assertEquals(
            SessionTabBounds(start = 6, endExclusive = 11),
            sessionTabBounds(listOf("one", "two"), 1),
        )
        assertNotNull(sessionTabBounds(listOf("first", "second"), 1))
    }

    test("primary and secondary pointer actions retain exact tab identity") {
        val first = SessionTabIdentity("first")
        val second = SessionTabIdentity("second")
        val state = SessionTabBarState(
            tabs = listOf(
                SessionTabPresentation(first, "First"),
                SessionTabPresentation(second, "Second"),
            ),
            selected = first,
        )
        val callbacks = RecordingCallbacks()
        var menu: SessionTabContextMenuRequest? = null

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(80)) {
                    SessionTabBar(
                        state = state,
                        callbacks = callbacks,
                        runningIndicatorFrame = fixedRunningIndicatorFrame,
                        columns = 80,
                        onOpenTabMenu = { request ->
                            menu = request
                            // The Application menu adapter may close the exact
                            // admitted identity from the menu action.
                            callbacks.close(request.identity)
                        },
                    )
                }
            }

            sendMouseEvent(MouseEvent(12, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(12, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            assertEquals(listOf(first), callbacks.selected)
            assertTrue(callbacks.menus.isEmpty())

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals(listOf(second), callbacks.menus)
        assertEquals(listOf(second), callbacks.closed)
        assertEquals(second, menu?.identity)
        assertEquals("Second", menu?.label)
    }

    test("a removed identity is rejected before a delayed pointer callback") {
        val first = SessionTabIdentity("first")
        val second = SessionTabIdentity("second")
        var state by mutableStateOf(
            SessionTabBarState(
                listOf(
                    SessionTabPresentation(first, "First"),
                    SessionTabPresentation(second, "Second"),
                ),
                first,
            ),
        )
        val callbacks = RecordingCallbacks()

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(80)) {
                    SessionTabBar(
                        state = state,
                        callbacks = callbacks,
                        runningIndicatorFrame = fixedRunningIndicatorFrame,
                        columns = 80,
                    )
                }
            }
            state = SessionTabBarState(
                listOf(SessionTabPresentation(first, "First")),
                first,
            )
            val replacement = awaitSnapshot()
            assertTrue("[First]" in replacement, replacement)
            assertFalse("[Second]" in replacement, replacement)
            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Release))
        }

        assertTrue(callbacks.selected.isEmpty())
        assertTrue(callbacks.menus.isEmpty())
        assertTrue(callbacks.closed.isEmpty())
    }
}

private class RecordingCallbacks : SessionTabBarCallbacks {
    val selected = mutableListOf<SessionTabIdentity>()
    val closed = mutableListOf<SessionTabIdentity>()
    val menus = mutableListOf<SessionTabIdentity>()
    var newSessionCount = 0
    var sessionsCount = 0

    override fun select(identity: SessionTabIdentity) {
        selected += identity
    }

    override fun close(identity: SessionTabIdentity) {
        closed += identity
    }

    override fun openContextMenu(identity: SessionTabIdentity) {
        menus += identity
    }

    override fun selectNewSession() {
        newSessionCount++
    }

    override fun openSessions() {
        sessionsCount++
    }
}
