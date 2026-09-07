package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val runningIndicatorTest by testSuite {
    test("framesUseTheClassicSingleCellBrailleSequence") {
        assertEquals(
            listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"),
            RunningIndicatorFrames,
        )
        assertEquals(
            List(RunningIndicatorFrames.size) { 1 },
            RunningIndicatorFrames.map(String::terminalCellWidth),
        )
        assertEquals(100, RunningIndicatorFrameDurationMillis)
    }

    test("runningLabelDirectlyPrefixesTheFrame") {
        assertEquals(
            "⠋Session name",
            runningIndicatorLabel(name = "Session name", running = true, frame = "⠋"),
        )
        assertEquals(
            "Session name",
            runningIndicatorLabel(name = "Session name", running = false, frame = "⠋"),
        )
    }

    test("composeInfiniteAnimationAdvancesAndResetsWhenInactive") {
        var active by mutableStateOf(true)

        runMosaicTest {
            val initial = setContentAndSnapshot {
                val frame by rememberRunningIndicatorFrame(active)
                Text(frame)
            }
            assertEquals("⠋", initial)

            val second = awaitSnapshotDifferentFrom(initial)
            assertEquals("⠙", second)
            assertEquals("⠹", awaitSnapshotDifferentFrom(second))

            active = false
            assertEquals("⠋", awaitSnapshotDifferentFrom("⠹"))
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotDifferentFrom(previous: String): String {
    repeat(20) {
        val snapshot = awaitSnapshot()
        if (snapshot != previous) return snapshot
    }
    error("The running indicator did not advance from $previous.")
}
