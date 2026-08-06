package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val scrollableStateTest by testSuite {
    test("scroll state clamps deltas and normalizes changing bounds") {
        val state = ScrollState(initial = 2)
        state.updateBounds(maxValue = 5, viewportSize = 3)

        assertEquals(-2, state.scrollBy(-10))
        assertEquals(0, state.value)
        assertFalse(state.canScrollBackward)
        assertTrue(state.canScrollForward)
        assertEquals(5, state.scrollBy(10))
        assertEquals(5, state.value)
        assertTrue(state.canScrollBackward)
        assertFalse(state.canScrollForward)
        assertEquals(0, state.scrollBy(1))

        state.updateBounds(maxValue = 1, viewportSize = 6)
        assertEquals(1, state.value)
        assertEquals(1, state.maxValue)
        assertEquals(6, state.viewportSize)
    }

    test("callback scroll state validates actual consumption") {
        lateinit var state: ScrollableState
        state = ScrollableState(
            consumeScrollDelta = { delta ->
                assertTrue(state.isScrollInProgress)
                delta / 2
            },
        )

        assertEquals(2, state.scrollBy(5))
        assertFalse(state.isScrollInProgress)

        val invalid = ScrollableState(consumeScrollDelta = { delta -> delta + 1 })
        assertFailsWith<IllegalArgumentException> { invalid.scrollBy(1) }
        assertFalse(invalid.isScrollInProgress)
    }

    test("wheel scrolling publishes only committed pointer interactions") {
        val state = ScrollState()
        state.updateBounds(maxValue = 4, viewportSize = 2)
        val interactionSource = MutableScrollInteractionSource()
        val interactions = mutableListOf<ScrollInteraction>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            interactionSource.interactions.take(2).toList(interactions)
        }

        runMosaicTest {
            assertEquals(
                "0",
                setContentAndSnapshot {
                    Text(
                        value = state.value.toString(),
                        modifier = Modifier
                            .width(4)
                            .height(1)
                            .scrollable(
                                state = state,
                                interactionSource = interactionSource,
                                wheelScrollLines = 3,
                            ),
                    )
                },
            )

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("3", awaitSnapshot())
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("4", awaitSnapshot())
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Release, MouseEvent.Button.WheelDown))
        }
        collector.join()

        assertEquals(
            listOf(
                ScrollInteraction(ScrollInputSource.Pointer, ScrollOrientation.Vertical, 3, 3),
                ScrollInteraction(ScrollInputSource.Pointer, ScrollOrientation.Vertical, 3, 1),
            ),
            interactions,
        )
    }

    test("disabled and reversed scrolling preserve direction semantics") {
        val state = ScrollState()
        state.updateBounds(maxValue = 4, viewportSize = 2)
        var enabled by mutableStateOf(false)

        runMosaicTest {
            setContentAndSnapshot {
                Text(
                    value = state.value.toString(),
                    modifier = Modifier
                        .width(4)
                        .height(1)
                        .scrollable(
                            state = state,
                            enabled = enabled,
                            reverseDirection = true,
                            wheelScrollLines = 2,
                        ),
                )
            }

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            enabled = true
            assertEquals("0", awaitSnapshot())
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            assertEquals("2", awaitSnapshot())
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("0", awaitSnapshot())
        }
    }

    test("zero consumption lets wheel input bubble to the parent") {
        val state = ScrollState()
        state.updateBounds(maxValue = 2, viewportSize = 1)
        var parentConsumptions by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box(
                    modifier = Modifier
                        .width(4)
                        .height(1)
                        .onPointerEvent { event ->
                            if (event.button == MouseEvent.Button.WheelUp) {
                                parentConsumptions++
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    Text(
                        value = "${state.value}:$parentConsumptions",
                        modifier = Modifier
                            .width(4)
                            .height(1)
                            .scrollable(state = state, wheelScrollLines = 1),
                    )
                }
            }

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            assertEquals("0:1", awaitSnapshot())
            assertEquals(1, parentConsumptions)
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("1:1", awaitSnapshot())
            assertEquals(1, parentConsumptions)
        }
    }

    test("paging uses the measured viewport and publishes keyboard interactions") {
        val state = ScrollState()
        state.updateBounds(maxValue = 5, viewportSize = 3)
        val interactionSource = MutableScrollInteractionSource()
        val interactions = mutableListOf<ScrollInteraction>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            interactionSource.interactions.take(2).toList(interactions)
        }

        runMosaicTest {
            setContentAndSnapshot {
                Text(
                    value = state.value.toString(),
                    modifier = Modifier
                        .width(4)
                        .height(1)
                        .scrollablePaging(
                            state = state,
                            viewportSize = { state.viewportSize },
                            interactionSource = interactionSource,
                        ),
                )
            }

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
            assertEquals("3", awaitSnapshot())
            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
            assertEquals("5", awaitSnapshot())
            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
        }
        collector.join()

        assertEquals(
            listOf(
                ScrollInteraction(ScrollInputSource.Keyboard, ScrollOrientation.Vertical, 3, 3),
                ScrollInteraction(ScrollInputSource.Keyboard, ScrollOrientation.Vertical, 3, 2),
            ),
            interactions,
        )
    }

    test("paging accepts a caller-defined page size") {
        val state = ScrollState()
        state.updateBounds(maxValue = 10, viewportSize = 5)

        runMosaicTest {
            setContentAndSnapshot {
                Text(
                    value = state.value.toString(),
                    modifier = Modifier.scrollablePaging(
                        state = state,
                        viewportSize = { state.viewportSize },
                        pageSize = { viewportSize -> viewportSize / 2 },
                    ),
                )
            }

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
            assertEquals("2", awaitSnapshot())
            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
            assertEquals("0", awaitSnapshot())
        }
    }

    test("modified paging is ignored and a boundary event bubbles") {
        val state = ScrollState()
        state.updateBounds(maxValue = 3, viewportSize = 2)
        var parentConsumptions by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box(
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.key == "PageUp") {
                            parentConsumptions++
                            true
                        } else {
                            false
                        }
                    },
                ) {
                    Text(
                        value = "${state.value}:$parentConsumptions",
                        modifier = Modifier.scrollablePaging(
                            state = state,
                            viewportSize = { state.viewportSize },
                        ),
                    )
                }
            }

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.PageDown,
                    modifiers = KeyboardEvent.ModifierShift,
                )
            )
            assertEquals(0, state.value)
            assertEquals(0, parentConsumptions)
            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
            assertEquals("0:1", awaitSnapshot())
        }
    }
}
