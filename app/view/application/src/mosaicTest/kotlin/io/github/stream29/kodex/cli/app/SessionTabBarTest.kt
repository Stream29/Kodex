package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
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
        assertEquals(
            "Review session title catalog · 5m ago",
            SessionCatalogEntry(
                sessionIndex = 7,
                threadName = "Review session title catalog",
                lastActivityAt = Instant.parse("2026-07-31T10:25:00Z"),
            ).sessionBrowserLabel(80, now),
        )
        assertEquals(
            "Review... · 5m ago",
            SessionCatalogEntry(
                sessionIndex = 7,
                threadName = "Review session title catalog",
                lastActivityAt = Instant.parse("2026-07-31T10:25:00Z"),
            ).sessionBrowserLabel(18, now),
        )
        assertEquals(
            "Session 7",
            SessionCatalogEntry(sessionIndex = 7).sessionBrowserLabel(80, now),
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
    fun selectedSessionTabIsBold() = runTest {
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

                assertTrue(Regex("\u001B\\[(?:[0-9]+;)*1m\\[First]").containsMatchIn(snapshot), snapshot)
                assertFalse(Regex("\u001B\\[(?:[0-9]+;)*1m\\[Second]").containsMatchIn(snapshot), snapshot)
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
