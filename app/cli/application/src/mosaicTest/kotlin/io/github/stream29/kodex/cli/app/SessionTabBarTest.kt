package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.session.RootSessionEntry
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SessionTabBarTest {
    @Test
    fun sessionBrowserUsesTheCatalogTitleAndLastActivityWithANumericFallback() {
        val now = Instant.parse("2026-07-31T10:30:00Z")
        assertEquals(
            "Review session title catalog · 5m ago",
            RootSessionEntry(
                sessionIndex = 7,
                viewModel = null,
                selected = false,
                threadName = "Review session title catalog",
                lastActivityAt = Instant.parse("2026-07-31T10:25:00Z"),
            ).sessionBrowserLabel(80, now),
        )
        assertEquals(
            "Review... · 5m ago",
            RootSessionEntry(
                sessionIndex = 7,
                viewModel = null,
                selected = false,
                threadName = "Review session title catalog",
                lastActivityAt = Instant.parse("2026-07-31T10:25:00Z"),
            ).sessionBrowserLabel(18, now),
        )
        assertEquals(
            "Session 7",
            RootSessionEntry(sessionIndex = 7, viewModel = null, selected = false).sessionBrowserLabel(80, now),
        )
    }

    @Test
    fun renamedNewSessionDisplaysItsDraftTitle() = runTest {
        val target = SessionTabTarget.NewSession(id = 1, ordinal = 1)

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box(Modifier.width(80)) {
                    SessionTabBar(
                        tabs = listOf(
                            SessionTabViewState(
                                target = target,
                                selected = true,
                                newSessionName = "Research plan",
                            ),
                        ),
                        columns = 80,
                        onSelectTab = {},
                        onOpenTabMenu = { _, _, _ -> },
                        onCreateNewSession = {},
                        onOpenSessions = {},
                    )
                }
            }

            assertTrue("Research plan" in snapshot, snapshot)
        }
    }

    @Test
    fun secondaryClickTargetsAnInactiveTabWithoutSelectingIt() = runTest {
        val activeTarget = SessionTabTarget.NewSession(id = 1, ordinal = 1)
        val inactiveTarget = SessionTabTarget.NewSession(id = 2, ordinal = 2)
        var selectedTarget by mutableStateOf<SessionTabTarget?>(null)
        var menuTarget by mutableStateOf<SessionTabTarget?>(null)
        var menuInitialName by mutableStateOf<String?>(null)

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    SessionTabBar(
                        tabs = listOf(
                            SessionTabViewState(
                                target = activeTarget,
                                selected = true,
                                newSessionName = "First",
                            ),
                            SessionTabViewState(
                                target = inactiveTarget,
                                selected = false,
                                newSessionName = "Second",
                            ),
                        ),
                        columns = 80,
                        onSelectTab = { target -> selectedTarget = target },
                        onOpenTabMenu = { target, initialName, _ ->
                            menuTarget = target
                            menuInitialName = initialName
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
            assertEquals(inactiveTarget, menuTarget)
            assertEquals("Second", menuInitialName)
        }
    }

    @Test
    fun primaryClickSelectsATabWithoutOpeningItsMenu() = runTest {
        val target = SessionTabTarget.NewSession(id = 1, ordinal = 1)
        var selectedTarget by mutableStateOf<SessionTabTarget?>(null)
        var menuTarget by mutableStateOf<SessionTabTarget?>(null)

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    SessionTabBar(
                        tabs = listOf(
                            SessionTabViewState(
                                target = target,
                                selected = true,
                                newSessionName = "First",
                            ),
                        ),
                        columns = 80,
                        onSelectTab = { selected -> selectedTarget = selected },
                        onOpenTabMenu = { opened, _, _ -> menuTarget = opened },
                        onCreateNewSession = {},
                        onOpenSessions = {},
                    )
                    Text("${selectedTarget != null}/${menuTarget != null}")
                }
            }

            sendMouseEvent(MouseEvent(12, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(12, 0, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(target, selectedTarget)
            assertEquals(null, menuTarget)
        }
    }
}
