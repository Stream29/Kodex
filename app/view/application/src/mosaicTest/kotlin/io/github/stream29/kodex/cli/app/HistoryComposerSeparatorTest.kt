package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val historySeparatorAnsiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val historyComposerSeparatorTest by testSuite {
    test("scroll-to-latest button changes from supporting text to bold on hover") {
        runMosaicTest(snapshotStrategy = historySeparatorAnsiSnapshots) {
            val idle = setContentAndSnapshot {
                HistoryComposerSeparator(
                    columns = 12,
                    showScrollToLatest = true,
                )
            }
            assertEquals("\u001B[2m-----[↓]----\u001B[0m", idle)

            sendMouseEvent(MouseEvent(6, 0, MouseEvent.Type.Motion))
            val hovered = awaitSnapshot()
            assertTrue(BoldScrollToLatestButton.containsMatchIn(hovered), hovered)
        }
    }

    test("scroll-to-latest button overlays the separator only while requested") {
        var showButton by mutableStateOf(false)
        var clicks by mutableStateOf(0)

        runMosaicTest {
            assertEquals(
                "------------",
                setContentAndSnapshot {
                    HistoryComposerSeparator(
                        columns = 12,
                        showScrollToLatest = showButton,
                        onScrollToLatest = {
                            clicks++
                            showButton = false
                        },
                    )
                },
            )

            showButton = true
            assertEquals("-----[↓]----", awaitSnapshot())
            sendMouseEvent(MouseEvent(6, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(6, 0, MouseEvent.Type.Release))

            var settled = awaitSnapshot()
            assertEquals(1, clicks)
            repeat(2) {
                if (settled != "------------") settled = awaitSnapshot()
            }
            assertEquals("------------", settled)
        }
    }
}

private val BoldScrollToLatestButton: Regex =
    Regex("\u001B\\[(?:[0-9]+;)*1(?:;[0-9]+)*m\\[↓]")
