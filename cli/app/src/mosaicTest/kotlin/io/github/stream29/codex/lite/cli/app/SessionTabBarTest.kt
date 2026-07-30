package io.github.stream29.codex.lite.cli.app

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.codex.lite.cli.components.rememberTuiPopupAnchor
import io.github.stream29.codex.lite.cli.session.RootSessionEntry
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
                        tabMenuAnchor = rememberTuiPopupAnchor(),
                        onSelectTab = {},
                        onCreateNewSession = {},
                        onOpenSessions = {},
                    )
                }
            }

            assertTrue("Research plan" in snapshot, snapshot)
        }
    }
}
