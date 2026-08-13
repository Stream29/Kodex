package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RunningIndicatorTest {
    @Test
    fun framesUseTheClassicSingleCellBrailleSequence() {
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

    @Test
    fun runningLabelDirectlyPrefixesTheFrame() {
        assertEquals(
            "⠋Session name",
            runningIndicatorLabel(name = "Session name", running = true, frame = "⠋"),
        )
        assertEquals(
            "Session name",
            runningIndicatorLabel(name = "Session name", running = false, frame = "⠋"),
        )
    }

    @Test
    fun composeInfiniteAnimationAdvancesAndResetsWhenInactive() = runTest {
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
