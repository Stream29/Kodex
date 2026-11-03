package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

val historyTurnMarkerParityTest by testSuite {
    test("terminal keeps its existing rounded turn marker") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers { HistoryTurnMarker(1500.microseconds) }
            }
            assertEquals("---Worked for 2ms---", snapshot)
        }
    }
    test("terminal keeps its existing infinite turn marker") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers { HistoryTurnMarker(Duration.INFINITE) }
            }
            assertEquals("---Worked for Infinity---", snapshot)
        }
    }
}
