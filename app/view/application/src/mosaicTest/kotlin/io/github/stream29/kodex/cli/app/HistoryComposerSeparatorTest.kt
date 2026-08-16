package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val historyComposerSeparatorTest by testSuite {
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
