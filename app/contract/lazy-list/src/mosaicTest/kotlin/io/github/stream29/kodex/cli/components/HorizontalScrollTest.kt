package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val horizontalScrollTest by testSuite {
    test("eager content is clipped and placed from the current column offset") {
        val state = ScrollState(initial = 2)

        runMosaicTest {
            assertEquals(
                "CDEF",
                setContentAndSnapshot {
                    ScrollableText(state = state, viewportWidth = 4)
                },
            )
        }

        assertEquals(2, state.value)
        assertEquals(6, state.maxValue)
        assertEquals(4, state.viewportSize)
    }

    test("wheel and page keys share the horizontal scroll state") {
        val state = ScrollState()

        runMosaicTest {
            assertEquals(
                "ABCD",
                setContentAndSnapshot {
                    ScrollableText(state = state, viewportWidth = 4)
                },
            )

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("DEFG", awaitSnapshot())
            assertEquals(3, state.value)

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
            assertEquals("GHIJ", awaitSnapshot())
            assertEquals(6, state.value)

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
            assertEquals("CDEF", awaitSnapshot())
            assertEquals(2, state.value)
        }
    }

    test("an unbounded horizontal axis is rejected") {
        runMosaicTest {
            assertFailsWith<IllegalStateException> {
                setContentAndSnapshot {
                    Row(Modifier.horizontalScroll(ScrollState())) {
                        Text("content")
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ScrollableText(state: ScrollState, viewportWidth: Int) {
    Row(
        modifier = Modifier
            .width(viewportWidth)
            .horizontalScroll(state),
    ) {
        Text("ABCDEFGHIJ")
    }
}
