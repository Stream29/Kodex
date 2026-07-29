package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.cli.action.TuiAction
import io.github.stream29.codex.lite.cli.action.TuiActionHost
import io.github.stream29.codex.lite.cli.action.TuiActionScope
import io.github.stream29.codex.lite.cli.action.TuiShortcut
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.assertEquals

private val ansiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val tuiPressableTest by testSuite {
    test("pressed button uses reverse video until release") {
        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            setContentAndSnapshot {
                TuiActionHost {
                    TuiButton(label = "Run") {}
                }
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            assertEquals("\u001B[7m[Run]\u001B[0m", awaitSnapshot())

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
                    TuiActionHost {
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

        runMosaicTest {
            setContentAndSnapshot {
                TuiActionHost {
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
                    TuiActionHost {
                        TuiButton(label = "Unavailable", enabled = false) {}
                    }
                },
            )
        }
    }

    test("shortcut, focused key, and captured pointer activation share one action") {
        var newCount by mutableStateOf(0)
        var forkCount by mutableStateOf(0)
        val newAction = TuiAction(
            id = "new",
            label = "New",
            shortcut = TuiShortcut(key = "n", ctrl = true),
        ) {
            newCount++
        }
        val forkAction = TuiAction(
            id = "fork",
            label = "Fork",
        ) {
            forkCount++
        }

        runMosaicTest {
            setContentAndSnapshot {
                TuiActionHost {
                    TuiActionScope(actions = listOf(newAction, forkAction)) {
                        Row {
                            TuiButton(action = newAction)
                            Text(" ")
                            TuiButton(action = forkAction)
                            Text(" $newCount/$forkCount")
                        }
                    }
                }
            }

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'n'.code,
                    modifiers = KeyboardEvent.ModifierCtrl,
                ),
            )
            awaitSnapshotAfter("shortcut")
            assertEquals(1, newCount)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotAfter("focused Enter")
            assertEquals(2, newCount)

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
}

private suspend fun TestMosaic<String>.awaitSnapshotAfter(stage: String): String = try {
    awaitSnapshot()
} catch (failure: TimeoutCancellationException) {
    throw AssertionError("No frame was produced after $stage.", failure)
}
