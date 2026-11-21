package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.collectAsState
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
import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val ansi16Snapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.ANSI16, supportsKittyUnderlines = false)
}

private val fixedRunningIndicatorFrame = mutableStateOf("⠋")

val sessionTabBarTest by testSuite {
    test("renamedPersistedSessionDisplaysItsTitle") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.persistedSession("Session")
            target.rename("Research plan")
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { target.name.first { it == "Research plan" } }
            }

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        Box(Modifier.width(80)) {
                            val name by target.name.collectAsState()
                            SessionTabBar(
                                tabs = listOf(SessionTabRenderState(target = target, selected = true, sessionName = name)),
                                runningIndicatorFrame = fixedRunningIndicatorFrame,
                                columns = 80,
                                onSelectTab = {},
                                onOpenTabMenu = { _, _, _, _ -> },
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                        }
                    }
                }

                assertTrue("Research plan" in snapshot, snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    test("selectedAgentTabUsesReverseVideo") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val selected = fixture.persistedSession("First")
            val second = fixture.persistedSession("Second")
            val inactive = object : AgentTabViewModel by second {}

            runMosaicTest(snapshotStrategy = ansi16Snapshots) {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
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
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                        }
                    }
                }

                assertTrue(Regex("\u001B\\[(?:[0-9]+;)*7m\\[First]").containsMatchIn(snapshot), snapshot)
                assertFalse(Regex("\u001B\\[(?:[0-9]+;)*7m\\[Second]").containsMatchIn(snapshot), snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    test("runningSessionTabPrefixesTheSpinnerBeforeTruncatingTheName") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.persistedSession("Long session")

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
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
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                        }
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

    test("overflowingTabsRemainReachableWithHorizontalPageKeys") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val targets = (0 until 10).map { index ->
                fixture.persistedSession("Tab $index")
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
                    WithMosaicComponentRenderers {
                        Box(Modifier.width(32)) {
                            SessionTabBar(
                                tabs = tabs,
                                runningIndicatorFrame = fixedRunningIndicatorFrame,
                                columns = 32,
                                onSelectTab = {},
                                onOpenTabMenu = { _, _, _, _ -> },
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                        }
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

    test("overflowingTabsRespondToNativeHorizontalWheelInput") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val targets = (0 until 10).map { index ->
                fixture.persistedSession("Tab $index")
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
                    WithMosaicComponentRenderers {
                        Box(Modifier.width(32)) {
                            SessionTabBar(
                                tabs = tabs,
                                runningIndicatorFrame = fixedRunningIndicatorFrame,
                                columns = 32,
                                onSelectTab = {},
                                onOpenTabMenu = { _, _, _, _ -> },
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                        }
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

    test("selectedOverflowingTabAutomaticallyEntersTheViewport") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val targets = (0 until 10).map { index ->
                fixture.persistedSession("Tab $index")
            }

            runMosaicTest {
                setContentAndSnapshot {
                    WithMosaicComponentRenderers {
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
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                        }
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

    test("secondaryClickTargetsAnInactiveTabWithoutSelectingIt") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val activeTarget = fixture.persistedSession("First")
            val inactiveTarget = fixture.persistedSession("Second")
            var selectedTarget by mutableStateOf<AgentTabViewModel?>(null)
            var menuTarget by mutableStateOf<AgentTabViewModel?>(null)
            var menuInitialName by mutableStateOf<String?>(null)
            var menuClickPosition by mutableStateOf<IntOffset?>(null)

            runMosaicTest {
                setContentAndSnapshot {
                    WithMosaicComponentRenderers {
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
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                            Text("${selectedTarget != null}/${menuTarget != null}/$menuInitialName")
                        }
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

    test("primaryClickSelectsATabWithoutOpeningItsMenu") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val target = fixture.persistedSession("First")
            var selectedTarget by mutableStateOf<AgentTabViewModel?>(null)
            var menuTarget by mutableStateOf<AgentTabViewModel?>(null)

            runMosaicTest {
                setContentAndSnapshot {
                    WithMosaicComponentRenderers {
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
                                onSelectNewSession = {},
                                onOpenSessions = {},
                            )
                            Text("${selectedTarget != null}/${menuTarget != null}")
                        }
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
