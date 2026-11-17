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
import kotlin.time.Duration.Companion.milliseconds

private val historySeparatorAnsiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val historyComposerSeparatorTest by testSuite {
    test("scroll-to-latest button changes from supporting text to bold on hover") {
        runMosaicTest(snapshotStrategy = historySeparatorAnsiSnapshots) {
            val idle = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    AgentHistorySeparatorContent(
                        liveDuration = null,
                        showScrollToLatest = true,
                        onScrollToLatest = {},
                        renderer = MosaicHistorySeparatorRenderer(columns = 12),
                    )
                }
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
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        AgentHistorySeparatorContent(
                            liveDuration = null,
                            showScrollToLatest = showButton,
                            onScrollToLatest = {
                                clicks++
                                showButton = false
                            },
                            renderer = MosaicHistorySeparatorRenderer(columns = 12),
                        )
                    }
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

    test("live duration is rounded to whole seconds") {
        assertEquals("Worked for 2s", liveTurnDurationLabel(1_500.milliseconds))
        assertEquals("Worked for 1s", liveTurnDurationLabel(1_499.milliseconds))
    }

    test("live duration is rendered at the left of the composer separator") {
        runMosaicTest(snapshotStrategy = historySeparatorAnsiSnapshots) {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    AgentHistorySeparatorContent(
                        liveDuration = 1_500.milliseconds,
                        showScrollToLatest = false,
                        onScrollToLatest = {},
                        renderer = MosaicHistorySeparatorRenderer(columns = 30),
                    )
                }
            }
            val plain = snapshot.replace(AnsiStyleEscape, "")
            assertTrue(plain.startsWith("Worked for 2s"), plain)
        }
    }

    test("live duration does not displace the centered scroll button") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    AgentHistorySeparatorContent(
                        liveDuration = 1_500.milliseconds,
                        showScrollToLatest = true,
                        onScrollToLatest = {},
                        renderer = MosaicHistorySeparatorRenderer(columns = 30),
                    )
                }
            }

            assertEquals("Worked for 2s-[↓]-------------", snapshot)
            assertEquals(14, snapshot.indexOf("[↓]"))
        }
    }
}

private val BoldScrollToLatestButton: Regex =
    Regex("\u001B\\[(?:[0-9]+;)*1(?:;[0-9]+)*m\\[↓]")

private val AnsiStyleEscape: Regex = Regex("\u001B\\[[0-9;]*m")
