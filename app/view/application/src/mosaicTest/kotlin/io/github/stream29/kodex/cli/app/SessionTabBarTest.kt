package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

private val ansi16Snapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.ANSI16, supportsKittyUnderlines = false)
}

private val fixedRunningIndicatorFrame = mutableStateOf("⠋")

class SessionTabBarTest {
    @Test
    fun sessionBrowserUsesTheCatalogTitleAndLastActivityWithANumericFallback() {
        val now = Instant.parse("2026-07-31T10:30:00Z")
        val fullLabel = SessionCatalogEntry(
            sessionIndex = 7,
            threadName = "Review session title catalog",
            lastActivityAt = Instant.parse("2026-07-31T10:25:00Z"),
        ).sessionBrowserLabel(80, now)
        assertEquals(
            "Review session title catalog 5m ago",
            fullLabel.text,
        )
        val activityStyle = fullLabel.spanStyles.single()
        assertEquals(fullLabel.text.indexOf(" 5m ago"), activityStyle.start)
        assertEquals(fullLabel.text.length, activityStyle.end)
        assertEquals(TextStyle.Dim, activityStyle.item.textStyle)
        assertEquals(
            "Review s... 5m ago",
            SessionCatalogEntry(
                sessionIndex = 7,
                threadName = "Review session title catalog",
                lastActivityAt = Instant.parse("2026-07-31T10:25:00Z"),
            ).sessionBrowserLabel(18, now).text,
        )
        assertEquals(
            "Session 7",
            SessionCatalogEntry(sessionIndex = 7).sessionBrowserLabel(80, now).text,
        )
    }

    @Test
    fun renamedNewSessionDisplaysItsDraftTitle() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.newSession("New Session")
            target.rename("Research plan")

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Box(Modifier.width(80)) {
                        val tabs = collectSessionTabRenderStates(listOf(target), selectedIndex = 0)
                        SessionTabBar(
                            tabs = tabs,
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 80,
                            onSelectTab = {},
                            onOpenTabMenu = { _, _, _, _ -> },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                    }
                }

                assertTrue("Research plan" in snapshot, snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectedSessionTabUsesReverseVideo() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val selected = fixture.newSession("First")
            val inactive = fixture.newSession("Second")

            runMosaicTest(snapshotStrategy = ansi16Snapshots) {
                val snapshot = setContentAndSnapshot {
                    Box(Modifier.width(80)) {
                        SessionTabBar(
                            tabs = listOf(
                                SessionTabRenderState(
                                    target = selected,
                                    selected = true,
                                    sessionName = "First",
                                ),
                                SessionTabRenderState(
                                    target = inactive,
                                    selected = false,
                                    sessionName = "Second",
                                ),
                            ),
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 80,
                            onSelectTab = {},
                            onOpenTabMenu = { _, _, _, _ -> },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                    }
                }

                assertTrue(Regex("\u001B\\[(?:[0-9]+;)*7m\\[First]").containsMatchIn(snapshot), snapshot)
                assertFalse(Regex("\u001B\\[(?:[0-9]+;)*7m\\[Second]").containsMatchIn(snapshot), snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun runningSessionTabPrefixesTheSpinnerBeforeTruncatingTheName() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.persistedSession("Long session")

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Box(Modifier.width(80)) {
                        SessionTabBar(
                            tabs = listOf(
                                SessionTabRenderState(
                                    target = target,
                                    selected = true,
                                    sessionName = "abcdefghijklmnopqrstuv",
                                    running = true,
                                ),
                            ),
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 80,
                            onSelectTab = {},
                            onOpenTabMenu = { _, _, _, _ -> },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                    }
                }

                assertTrue("[⠋abcdefghijklmnop...]" in snapshot, snapshot)
                assertFalse(" *" in snapshot, snapshot)
                assertFalse("⠋ " in snapshot, snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun overflowingTabsRemainReachableWithHorizontalPageKeys() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val targets = (0 until 10).map { index ->
                fixture.newSession("Tab $index")
            }
            val tabs = targets.mapIndexed { index, target ->
                SessionTabRenderState(
                    target = target,
                    selected = index == 0,
                    sessionName = "Tab $index",
                )
            }

            runMosaicTest {
                val initial = setContentAndSnapshot {
                    Box(Modifier.width(32)) {
                        SessionTabBar(
                            tabs = tabs,
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 32,
                            onSelectTab = {},
                            onOpenTabMenu = { _, _, _, _ -> },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                    }
                }
                assertTrue("[Tab 0]" in initial, initial)
                assertFalse("[Tab 9]" in initial, initial)
                assertTrue("[Sessions]" in initial && "[+]" in initial, initial)

                awaitSnapshot()
                repeat(4) {
                    sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
                }
                var latest = initial
                for (attempt in 0 until 12) {
                    latest = try {
                        awaitSnapshot()
                    } catch (_: TimeoutCancellationException) {
                        break
                    }
                    if ("[Tab 9]" in latest) break
                }

                assertTrue("[Tab 9]" in latest, latest)
                assertTrue("[Sessions]" in latest && "[+]" in latest, latest)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun overflowingTabsRespondToNativeHorizontalWheelInput() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val targets = (0 until 10).map { index ->
                fixture.newSession("Tab $index")
            }
            val tabs = targets.mapIndexed { index, target ->
                SessionTabRenderState(
                    target = target,
                    selected = index == 0,
                    sessionName = "Tab $index",
                )
            }

            runMosaicTest {
                val initial = setContentAndSnapshot {
                    Box(Modifier.width(32)) {
                        SessionTabBar(
                            tabs = tabs,
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 32,
                            onSelectTab = {},
                            onOpenTabMenu = { _, _, _, _ -> },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                    }
                }
                assertTrue("[Tab 0]" in initial, initial)
                assertFalse("[Tab 2]" in initial, initial)

                var scrolled = initial
                repeat(3) {
                    sendMouseEvent(
                        MouseEvent(12, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelRight),
                    )
                    scrolled = awaitSnapshot()
                }

                assertTrue("[Tab 2]" in scrolled, scrolled)
                assertTrue("[Sessions]" in scrolled && "[+]" in scrolled, scrolled)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectedOverflowingTabAutomaticallyEntersTheViewport() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val targets = (0 until 10).map { index ->
                fixture.newSession("Tab $index")
            }

            runMosaicTest {
                setContentAndSnapshot {
                    Box(Modifier.width(32)) {
                        SessionTabBar(
                            tabs = targets.mapIndexed { index, target ->
                                SessionTabRenderState(
                                    target = target,
                                    selected = index == targets.lastIndex,
                                    sessionName = "Tab $index",
                                )
                            },
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 32,
                            onSelectTab = {},
                            onOpenTabMenu = { _, _, _, _ -> },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                    }
                }
                val visible = awaitSnapshot()

                assertTrue("[Tab 9]" in visible, visible)
                assertFalse("[Tab 0]" in visible, visible)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun tabBoundsIncludeBracketsAndInterTabSpacing() {
        assertEquals(SessionTabBounds(start = 0, endExclusive = 5), sessionTabBounds(listOf("one"), 0))
        assertEquals(
            SessionTabBounds(start = 6, endExclusive = 11),
            sessionTabBounds(listOf("one", "two"), 1),
        )
        assertEquals(null, sessionTabBounds(listOf("one"), 2))
    }

    @Test
    fun secondaryClickTargetsAnInactiveTabWithoutSelectingIt() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val activeTarget = fixture.newSession("First")
            val inactiveTarget = fixture.newSession("Second")
            var selectedTarget by mutableStateOf<SessionViewModel?>(null)
            var menuTarget by mutableStateOf<SessionViewModel?>(null)
            var menuInitialName by mutableStateOf<String?>(null)
            var menuClickPosition by mutableStateOf<IntOffset?>(null)

            runMosaicTest {
                setContentAndSnapshot {
                    Column {
                        SessionTabBar(
                            tabs = listOf(
                                SessionTabRenderState(
                                    target = activeTarget,
                                    selected = true,
                                    sessionName = "First",
                                ),
                                SessionTabRenderState(
                                    target = inactiveTarget,
                                    selected = false,
                                    sessionName = "Second",
                                ),
                            ),
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 80,
                            onSelectTab = { target -> selectedTarget = target },
                            onOpenTabMenu = { target, initialName, _, clickPosition ->
                                menuTarget = target
                                menuInitialName = initialName
                                menuClickPosition = clickPosition
                            },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                        Text("${selectedTarget != null}/${menuTarget != null}/$menuInitialName")
                    }
                }

                sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
                sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Release))
                awaitSnapshot()

                assertEquals(null, selectedTarget)
                assertSame(inactiveTarget, menuTarget)
                assertEquals("Second", menuInitialName)
                assertEquals(IntOffset(x = 1, y = 0), menuClickPosition)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun primaryClickSelectsATabWithoutOpeningItsMenu() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.newSession("First")
            var selectedTarget by mutableStateOf<SessionViewModel?>(null)
            var menuTarget by mutableStateOf<SessionViewModel?>(null)

            runMosaicTest {
                setContentAndSnapshot {
                    Column {
                        SessionTabBar(
                            tabs = listOf(
                                SessionTabRenderState(
                                    target = target,
                                    selected = true,
                                    sessionName = "First",
                                ),
                            ),
                            runningIndicatorFrame = fixedRunningIndicatorFrame,
                            columns = 80,
                            onSelectTab = { selected -> selectedTarget = selected },
                            onOpenTabMenu = { opened, _, _, _ -> menuTarget = opened },
                            onCreateNewSession = {},
                            onOpenSessions = {},
                        )
                        Text("${selectedTarget != null}/${menuTarget != null}")
                    }
                }

                sendMouseEvent(MouseEvent(12, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
                sendMouseEvent(MouseEvent(12, 0, MouseEvent.Type.Release))
                awaitSnapshot()

                assertSame(target, selectedTarget)
                assertEquals(null, menuTarget)
            }
        } finally {
            fixture.close()
        }
    }
}
