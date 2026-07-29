package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.cli.action.TuiActionHost
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val popupAnsiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val tuiPopupTest by testSuite {
    test("above-start positioning prefers the space above its anchor") {
        val positionProvider = TuiPopupPositionProvider.AboveStart

        assertEquals(
            IntSize(80, 22),
            positionProvider.calculateMaximumSize(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(11, 22), IntSize(20, 1)),
                surfaceSize = IntSize(80, 24),
            ),
        )
        assertEquals(
            IntOffset(11, 15),
            positionProvider.calculatePosition(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(11, 22), IntSize(20, 1)),
                surfaceSize = IntSize(80, 24),
                popupContentSize = IntSize(10, 7),
            ),
        )
        assertEquals(
            IntOffset(11, 1),
            positionProvider.calculatePosition(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(11, 0), IntSize(20, 1)),
                surfaceSize = IntSize(80, 24),
                popupContentSize = IntSize(10, 7),
            ),
        )
        assertEquals(
            IntSize(80, 23),
            positionProvider.calculateMaximumSize(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(11, 0), IntSize(20, 1)),
                surfaceSize = IntSize(80, 24),
            ),
        )
        assertEquals(
            IntOffset(14, 4),
            positionProvider.calculatePosition(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(22, 7), IntSize(2, 1)),
                surfaceSize = IntSize(24, 8),
                popupContentSize = IntSize(10, 3),
            ),
        )
    }

    test("end-top positioning prefers the end side and falls back to the start side") {
        val positionProvider = TuiPopupPositionProvider.EndTop

        assertEquals(
            IntSize(62, 24),
            positionProvider.calculateMaximumSize(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(12, 9), IntSize(6, 1)),
                surfaceSize = IntSize(80, 24),
            ),
        )
        assertEquals(
            IntOffset(18, 9),
            positionProvider.calculatePosition(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(12, 9), IntSize(6, 1)),
                surfaceSize = IntSize(80, 24),
                popupContentSize = IntSize(10, 7),
            ),
        )
        assertEquals(
            IntOffset(12, 5),
            positionProvider.calculatePosition(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(22, 7), IntSize(2, 1)),
                surfaceSize = IntSize(24, 8),
                popupContentSize = IntSize(10, 3),
            ),
        )
        assertEquals(
            IntSize(22, 8),
            positionProvider.calculateMaximumSize(
                anchorBounds = TuiPopupAnchorBounds(IntOffset(22, 7), IntSize(2, 1)),
                surfaceSize = IntSize(24, 8),
            ),
        )
    }

    test("position providers clamp stale anchor bounds after a surface resize") {
        val staleAnchor = TuiPopupAnchorBounds(IntOffset(15, 7), IntSize(2, 1))

        assertEquals(
            IntOffset(6, 0),
            TuiPopupPositionProvider.AboveStart.calculatePosition(
                anchorBounds = staleAnchor,
                surfaceSize = IntSize(10, 4),
                popupContentSize = IntSize(4, 4),
            ),
        )
        assertEquals(
            IntOffset(6, 0),
            TuiPopupPositionProvider.EndTop.calculatePosition(
                anchorBounds = staleAnchor,
                surfaceSize = IntSize(10, 4),
                popupContentSize = IntSize(4, 4),
            ),
        )
    }

    test("popup content overlays the host at its measured anchor position") {
        runMosaicTest(snapshotStrategy = popupAnsiSnapshots) {
            setContentAndSnapshot {
                TuiActionHost {
                    PopupHarness()
                }
            }
            val initial = awaitSnapshot()

            val lines = initial.lines()
            assertTrue(lines[4].contains("[low   ]"), initial)
            assertTrue(lines[5].contains("[medium]"), initial)
            assertTrue(lines[6].contains("[high  ]"), initial)
            assertTrue(lines[7].contains("[trigger]"), initial)

            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Motion))
            val hovered = awaitSnapshot()
            assertTrue(hovered.contains("\u001B[1m[medium]\u001B[0m"), hovered)
        }
    }

    test("popup positioning is relative to its host rather than the terminal surface") {
        runMosaicTest {
            setContentAndSnapshot {
                TuiActionHost {
                    Column {
                        Text("header")
                        PopupHarness()
                    }
                }
            }
            val snapshot = awaitSnapshot()

            val lines = snapshot.lines()
            assertEquals("header", lines[0])
            assertTrue(lines[5].contains("[low   ]"), snapshot)
            assertTrue(lines[6].contains("[medium]"), snapshot)
            assertTrue(lines[7].contains("[high  ]"), snapshot)
            assertTrue(lines[8].contains("[trigger]"), snapshot)
        }
    }

    test("primary clicks outside popup content invoke the dismiss callback") {
        var expanded by mutableStateOf(true)

        runMosaicTest {
            setContentAndSnapshot {
                TuiActionHost {
                    PopupHarness(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    )
                }
            }
            awaitSnapshot()

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            assertFalse(expanded)
        }
    }

    test("popup content receives its own pointer selection before the dismiss layer") {
        var selectedIndex by mutableStateOf(-1)
        var dismissed by mutableStateOf(false)

        runMosaicTest {
            setContentAndSnapshot {
                TuiActionHost {
                    PopupHarness(
                        onDismissRequest = { dismissed = true },
                        onSelect = { selectedIndex = it },
                    )
                }
            }
            awaitSnapshot()

            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(1, selectedIndex)
            assertFalse(dismissed)
        }
    }

    test("nested popup content stays above its parent dismiss layer") {
        var childSelected by mutableStateOf(false)
        var dismissed by mutableStateOf(false)

        runMosaicTest {
            setContentAndSnapshot {
                TuiActionHost {
                    CascadingPopupHarness(
                        onDismissRequest = { dismissed = true },
                        onChildSelect = { childSelected = true },
                    )
                }
            }
            awaitSnapshot()
            awaitSnapshot()

            sendMouseEvent(MouseEvent(9, 6, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(9, 6, MouseEvent.Type.Release))
            awaitSnapshot()
            assertTrue(childSelected)
            assertFalse(dismissed)

            sendMouseEvent(MouseEvent(22, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            assertTrue(dismissed)
        }
    }
}

/** @param onDismissRequest `null` leaves outside pointer presses unhandled in this test harness. */
@androidx.compose.runtime.Composable
private fun PopupHarness(
    expanded: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onSelect: (Int) -> Unit = {},
) {
    val anchor = rememberTuiPopupAnchor()
    TuiPopupHost(
        modifier = Modifier
            .width(24)
            .height(8),
    ) {
        Column(modifier = Modifier.matchParentSize()) {
            repeat(7) {
                Text(" ")
            }
            Text(
                value = "[trigger]",
                modifier = Modifier.tuiPopupAnchor(anchor),
            )
        }
        if (expanded) {
            TuiPopup(
                anchor = anchor,
                onDismissRequest = onDismissRequest,
            ) {
                Column {
                    listOf("low   ", "medium", "high  ").forEachIndexed { index, label ->
                        TuiButton(label = label, onClick = { onSelect(index) })
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CascadingPopupHarness(
    onDismissRequest: () -> Unit,
    onChildSelect: () -> Unit,
) {
    val triggerAnchor = rememberTuiPopupAnchor()
    val parentItemAnchor = rememberTuiPopupAnchor()
    TuiPopupHost(
        modifier = Modifier
            .width(24)
            .height(8),
    ) {
        Column(modifier = Modifier.matchParentSize()) {
            repeat(7) {
                Text(" ")
            }
            Text(
                value = "[trigger]",
                modifier = Modifier.tuiPopupAnchor(triggerAnchor),
            )
        }
        TuiPopup(
            anchor = triggerAnchor,
            onDismissRequest = onDismissRequest,
        ) {
            Text(
                value = "[parent]",
                modifier = Modifier.tuiPopupAnchor(parentItemAnchor),
            )
        }
        TuiPopup(
            anchor = parentItemAnchor,
            positionProvider = TuiPopupPositionProvider.EndTop,
        ) {
            TuiButton(label = "child", onClick = onChildSelect)
        }
    }
}
