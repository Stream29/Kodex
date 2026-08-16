package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.assertEquals

private val ansiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val tuiPressableTest by testSuite {
    test("button applies its idle text style") {
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            assertEquals(
                "\u001B[1m[Current]\u001B[0m",
                setContentAndSnapshot {
                    Box {
                        TuiButton(
                            label = "Current",
                            idleTextStyle = TextStyle.Bold,
                        ) {}
                    }
                },
            )
        }
    }

    test("pressed button uses bold reverse video until release") {
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            setContentAndSnapshot {
                Box {
                    TuiButton(label = "Run") {}
                }
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            assertEquals("\u001B[1;7m[Run]\u001B[0m", awaitSnapshot())

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            assertEquals("\u001B[1m[Run]\u001B[0m", awaitSnapshot())

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Motion))
            assertEquals("[Run]", awaitSnapshot())
        }
    }

    test("hover uses bold without changing the button boundary or focus text") {
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            assertEquals(
                "[New] [Fork]",
                setContentAndSnapshot {
                    Box {
                        Row {
                            TuiButton(label = "New") {}
                            Text(" ")
                            TuiButton(label = "Fork") {}
                        }
                    }
                },
            )

            sendMouseEvent(MouseEvent(7, 0, MouseEvent.Type.Motion))
            assertEquals("[New] \u001B[1m[Fork]\u001B[0m", awaitSnapshot())

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Motion))
            assertEquals("[New] [Fork]", awaitSnapshot())
        }
    }

    test("dragging out and back in preserves pointer ownership") {
        var clickCount by mutableStateOf(0)

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            setContentAndSnapshot {
                Box {
                    Row {
                        TuiButton(label = "Run") { clickCount++ }
                        Text(" $clickCount")
                    }
                }
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshotAfter("pointer press")

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Drag, MouseEvent.Button.Left))
            awaitSnapshotAfter("drag outside")

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Drag, MouseEvent.Button.Left))
            awaitSnapshotAfter("drag inside")

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            awaitSnapshotAfter("inside release")
            assertEquals(1, clickCount)
        }
    }

    test("disabled button remains bracketed and dim") {
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            assertEquals(
                "\u001B[2m[Unavailable]\u001B[0m",
                setContentAndSnapshot {
                    Box {
                        TuiButton(label = "Unavailable", enabled = false) {}
                    }
                },
            )
        }
    }

    test("focused key and captured pointer activation invoke button callbacks") {
        var newCount by mutableStateOf(0)
        var forkCount by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    Row {
                        TuiButton(label = "New", onClick = { newCount++ })
                        Text(" ")
                        TuiButton(label = "Fork", onClick = { forkCount++ })
                        Text(" $newCount/$forkCount")
                    }
                }
            }

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotAfter("focused Enter")
            assertEquals(1, newCount)

            sendMouseEvent(MouseEvent(7, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshotAfter("pointer press")
            sendMouseEvent(MouseEvent(32, 3, MouseEvent.Type.Release))
            sendMouseEvent(MouseEvent(7, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshotAfter("second pointer press")
            assertEquals(0, forkCount)
            sendMouseEvent(MouseEvent(7, 0, MouseEvent.Type.Release))
            awaitSnapshotAfter("inside release")
            assertEquals(1, forkCount)
        }
    }

    test("secondary pointer Shift F10 and Menu invoke only the secondary callback") {
        var primaryCount by mutableStateOf(0)
        var secondaryCount by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    Row {
                        TuiButton(
                            label = "Session",
                            onSecondaryClick = { secondaryCount++ },
                            onClick = { primaryCount++ },
                        )
                        Text(" $primaryCount/$secondaryCount")
                    }
                }
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            awaitSnapshotAfter("secondary pointer release")
            assertEquals(0, primaryCount)
            assertEquals(1, secondaryCount)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.F10,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            awaitSnapshotAfter("Shift F10")
            assertEquals(0, primaryCount)
            assertEquals(2, secondaryCount)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Menu))
            awaitSnapshotAfter("Menu")
            assertEquals(0, primaryCount)
            assertEquals(3, secondaryCount)
        }
    }

    test("secondary callback distinguishes pointer position from keyboard activation") {
        var invocationCount by mutableStateOf(0)
        var position: IntOffset? by mutableStateOf(IntOffset(x = -1, y = -1))

        runMosaicTest {
            setContentAndSnapshot {
                Row {
                    TuiPressable(
                        onClick = {},
                        onSecondaryClick = { clickPosition ->
                            invocationCount++
                            position = clickPosition
                        },
                    ) { _, _, _ ->
                        Text("Session")
                    }
                    Text(" $invocationCount")
                }
            }

            sendMouseEvent(MouseEvent(3, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(3, 0, MouseEvent.Type.Release))
            awaitSnapshotAfter("secondary pointer position")
            assertEquals(1, invocationCount)
            assertEquals(IntOffset(x = 3, y = 0), position)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.F10,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            awaitSnapshotAfter("secondary keyboard activation")
            assertEquals(2, invocationCount)
            assertEquals(null, position)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Menu))
            awaitSnapshotAfter("Menu activation")
            assertEquals(3, invocationCount)
            assertEquals(null, position)
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotAfter(stage: String): String = try {
    awaitSnapshot()
} catch (failure: TimeoutCancellationException) {
    throw AssertionError("No frame was produced after $stage.", failure)
}
