package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

val sessionCatalogRunningIndicatorTest by testSuite {
    test("catalog label uses only backend running and reserves width for spinner") {
        val now = Instant.parse("2026-09-28T10:30:00Z")
        val updatedAt = Instant.parse("2026-09-28T10:25:00Z")
        val activeIdle = SessionCatalogEntry(
            sessionIndex = 1,
            threadName = "Idle",
            isActive = true,
        )
        val running = SessionCatalogEntry(
            sessionIndex = 2,
            threadName = "abcdefghijklmnop",
            updatedAt = updatedAt,
            running = true,
        )

        assertEquals("Idle", activeIdle.sessionBrowserLabel(18, now, "⠋").text)
        val label = running.sessionBrowserLabel(18, now, "⠋").text
        assertTrue(label.startsWith("⠋"), label)
        assertTrue(label.endsWith(" 5m ago"), label)
        assertEquals(18, label.terminalCellWidth())
    }

    test("catalog row redraws spinner frames for a running entry") {
        var frame by mutableStateOf("⠋")
        runMosaicTest {
            val first = setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(40).height(4)) {
                    SessionCatalogRow(
                        entry = SessionCatalogEntry(2, threadName = "Running", running = true),
                        maximumLabelColumns = 38,
                        runningIndicatorFrame = frame,
                        onClick = {},
                        onOpenContextMenu = { _, _ -> },
                    )
                }
            }
            assertTrue("[⠋Running]" in first, first)

            frame = "⠙"
            val next = awaitSnapshot()
            assertTrue("[⠙Running]" in next, next)
            assertFalse("[⠋Running]" in next, next)
        }
    }
}
