package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val verticalScrollTest by testSuite {
    test("eager content is clipped and placed from the current row offset") {
        val state = ScrollState(initial = 2)

        runMosaicTest {
            assertEquals(
                "C\nD\nE",
                setContentAndSnapshot {
                    ScrollableLetters(state = state, viewportHeight = 3, itemCount = 5)
                },
            )
        }

        assertEquals(2, state.value)
        assertEquals(2, state.maxValue)
        assertEquals(3, state.viewportSize)
    }

    test("state mutations relayout content and clamp when content shrinks") {
        val state = ScrollState()
        var itemCount by mutableIntStateOf(5)

        runMosaicTest {
            assertEquals(
                "A\nB",
                setContentAndSnapshot {
                    ScrollableLetters(state = state, viewportHeight = 2, itemCount = itemCount)
                },
            )

            assertEquals(1, state.scrollBy(1))
            assertEquals("B\nC", awaitSnapshot())

            itemCount = 1
            assertEquals("A\n", awaitSnapshot())
            assertEquals(0, state.value)
            assertEquals(0, state.maxValue)
            assertEquals(2, state.viewportSize)
        }
    }

    test("wheel input is wired to the same scroll state") {
        val state = ScrollState()

        runMosaicTest {
            setContentAndSnapshot {
                ScrollableLetters(state = state, viewportHeight = 2, itemCount = 5)
            }

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("D\nE", awaitSnapshot())
            assertEquals(3, state.value)
        }
    }

    test("reverse scrolling starts at the visual end and reverses wheel direction") {
        val state = ScrollState()

        runMosaicTest {
            assertEquals(
                "D\nE",
                setContentAndSnapshot {
                    ScrollableLetters(
                        state = state,
                        viewportHeight = 2,
                        itemCount = 5,
                        reverseScrolling = true,
                    )
                },
            )

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            assertEquals("A\nB", awaitSnapshot())
            assertEquals(3, state.value)
        }
    }

    test("an unbounded vertical axis is rejected") {
        runMosaicTest {
            assertFailsWith<IllegalStateException> {
                setContentAndSnapshot {
                    Column(Modifier.verticalScroll(ScrollState())) {
                        Text("content")
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ScrollableLetters(
    state: ScrollState,
    viewportHeight: Int,
    itemCount: Int,
    reverseScrolling: Boolean = false,
) {
    Column(
        modifier = Modifier
            .height(viewportHeight)
            .verticalScroll(state, reverseScrolling = reverseScrolling),
    ) {
        repeat(itemCount) { index ->
            Text(('A'.code + index).toChar().toString())
        }
    }
}
