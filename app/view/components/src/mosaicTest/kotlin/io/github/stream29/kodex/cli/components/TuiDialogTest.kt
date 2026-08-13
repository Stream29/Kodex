package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val tuiDialogTest by testSuite {
    test("dialog centers its content over the popup host") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        Text("background")
                        TuiDialog(onDismissRequest = {}) {
                            Text("[dialog]", modifier = Modifier.focusable())
                        }
                    }
                }
            }

            assertEquals("background", snapshot.lines()[0])
            assertEquals("        [dialog]", snapshot.lines()[4])
        }
    }

    test("dialog clears background characters inside its bounds") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(12)
                            .height(5),
                    ) {
                        Text(List(5) { "abcdefghijkl" }.joinToString("\n"))
                        TuiDialog(
                            onDismissRequest = {},
                            modifier = Modifier
                                .width(6)
                                .background(Color.Blue),
                        ) {
                            Text("X", modifier = Modifier.focusable())
                        }
                    }
                }
            }

            assertEquals("abcX     jkl", snapshot.lines()[2])
        }
    }

    test("escape dismisses a dialog and restores its prior focus") {
        var expanded by mutableStateOf(false)
        var backgroundFocused by mutableStateOf(false)
        var dialogFocused by mutableStateOf(false)
        var dialogKeyEvents by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        Text(
                            value = if (backgroundFocused) "<background>" else "[background]",
                            modifier = Modifier
                                .onFocusChanged { backgroundFocused = it == FocusState.Active }
                                .focusable(),
                        )
                        if (expanded) {
                            TuiDialog(onDismissRequest = { expanded = false }) {
                                Text(
                                    value = if (dialogFocused) "<dialog>" else "[dialog]",
                                    modifier = Modifier
                                        .onFocusChanged { dialogFocused = it == FocusState.Active }
                                        .onKeyEvent {
                                            dialogKeyEvents++
                                            true
                                        }
                                        .focusable(),
                                )
                            }
                        }
                    }
                }
            }

            awaitSnapshotContaining("<background>")
            expanded = true
            val open = awaitSnapshotContaining("<dialog>")
            assertTrue("[background]" in open, open)

            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshotContaining("<background>")
        }

        assertFalse(expanded)
        assertEquals(0, dialogKeyEvents)
    }

    test("escape dismisses a dialog without a focusable child") {
        var expanded by mutableStateOf(true)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        if (expanded) {
                            TuiDialog(onDismissRequest = { expanded = false }) {
                                Text("[dialog]")
                            }
                        }
                    }
                }
            }

            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshot()
        }

        assertFalse(expanded)
    }

    test("escape can use a callback separate from outside dismissal") {
        var escapeRequests by mutableStateOf(0)
        var dismissRequests by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        TuiDialog(
                            onDismissRequest = { dismissRequests++ },
                            onEscapeRequest = { escapeRequests++ },
                        ) {
                            Text(
                                "$dismissRequests:$escapeRequests",
                                modifier = Modifier.focusable(),
                            )
                        }
                    }
                }
            }

            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshotContaining("0:1")
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshotContaining("1:1")
        }

        assertEquals(1, escapeRequests)
        assertEquals(1, dismissRequests)
    }

    test("button dismissal restores the prior focus target") {
        var expanded by mutableStateOf(false)
        var backgroundFocused by mutableStateOf(false)
        var backgroundKeyEvents by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        Text(
                            value = if (backgroundFocused) {
                                "<background>$backgroundKeyEvents"
                            } else {
                                "[background]$backgroundKeyEvents"
                            },
                            modifier = Modifier
                                .onFocusChanged { backgroundFocused = it == FocusState.Active }
                                .onKeyEvent {
                                    backgroundKeyEvents++
                                    true
                                }
                                .focusable(),
                        )
                        if (expanded) {
                            TuiDialog(onDismissRequest = { expanded = false }) {
                                TuiButton(
                                    label = "Dismiss",
                                    onClick = { expanded = false },
                                )
                            }
                        }
                    }
                }
            }

            awaitSnapshotContaining("<background>0")
            expanded = true
            awaitSnapshotContaining("[Dismiss]")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("<background>0")
            sendKeyEvent(KeyboardEvent(codepoint = 'z'.code))
            awaitSnapshotContaining("<background>1")
        }

        assertFalse(expanded)
        assertEquals(1, backgroundKeyEvents)
    }

    test("outside dialog pointer input dismisses without reaching the background") {
        var expanded by mutableStateOf(true)
        var backgroundPresses by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        TuiButton(label = "background") {
                            backgroundPresses++
                        }
                        if (expanded) {
                            TuiDialog(onDismissRequest = { expanded = false }) {
                                Text("[dialog]", modifier = Modifier.focusable())
                            }
                        }
                    }
                }
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
        }

        assertFalse(expanded)
        assertEquals(0, backgroundPresses)
    }

    test("dialog content handles pointer input without dismissal") {
        var dismissed by mutableStateOf(false)
        var dialogPresses by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        TuiDialog(onDismissRequest = { dismissed = true }) {
                            TuiButton(label = "inside") {
                                dialogPresses++
                            }
                        }
                    }
                }
            }

            sendMouseEvent(MouseEvent(9, 4, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(9, 4, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertFalse(dismissed)
        assertEquals(1, dialogPresses)
    }

    test("dialog passes non-Escape keys to focused content") {
        var dialogKeyEvents by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(
                        modifier = Modifier
                            .width(24)
                            .height(8),
                    ) {
                        TuiDialog(onDismissRequest = {}) {
                            Text(
                                value = dialogKeyEvents.toString(),
                                modifier = Modifier
                                    .onKeyEvent {
                                        dialogKeyEvents++
                                        true
                                    }
                                    .focusable(),
                            )
                        }
                    }
                }
            }
            awaitSnapshot()

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'n'.code,
                    modifiers = KeyboardEvent.ModifierCtrl,
                ),
            )
            awaitSnapshot()
        }

        assertEquals(1, dialogKeyEvents)
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(3) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            return@repeat
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
