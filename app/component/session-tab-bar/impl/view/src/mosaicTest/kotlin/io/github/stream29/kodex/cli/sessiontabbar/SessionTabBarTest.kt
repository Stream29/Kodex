package io.github.stream29.kodex.cli.sessiontabbar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
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

    override fun createNewSession() {
        newSessionCount++
    }

    override fun openSessions() {
        sessionsCount++
    }
}
