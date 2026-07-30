package io.github.stream29.codex.lite.cli.app

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.codex.lite.cli.components.rememberTuiPopupAnchor
import io.github.stream29.codex.lite.cli.session.RootSessionEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SessionTabBarTest {
    @Test
    fun sessionBrowserUsesTheCatalogTitleWithANumericFallback() {
        assertEquals(
            "Review session title catalog",
            RootSessionEntry(
                sessionIndex = 7,
                viewModel = null,
                selected = false,
                threadName = "Review session title catalog",
            ).sessionBrowserLabel(80),
        )
        assertEquals(
            "Session 7",
            RootSessionEntry(sessionIndex = 7, viewModel = null, selected = false).sessionBrowserLabel(80),
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
