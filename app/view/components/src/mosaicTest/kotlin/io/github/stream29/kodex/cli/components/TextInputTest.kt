package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private val ansiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val textInputTest by testSuite {
    test("disabled input is dim and rejects keyboard edits") {
        val state = TextInputState(TextInputValue(text = "locked", cursorOffset = 6))

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            assertEquals(
                "\u001B[2mlocked\u001B[0m",
                setContentAndSnapshot {
                    TextInput(
                        state = state,
                        layout = TextInputLayout.create(value = state.value, width = 80),
                        enabled = false,
                    )
                },
            )

            sendKeyEvent(KeyboardEvent(codepoint = 'x'.code))

            assertEquals(TextInputValue(text = "locked", cursorOffset = 6), state.value)
        }
    }

    test("paste inserts normalized content through input state") {
        val state = TextInputState()
        val pasted = "首行\t😀\r\nsecond\rthird"
        val expected = "首行\t😀\nsecond\nthird"

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(value = state.value, width = 80),
                    autoFocus = true,
                )
            }

            sendPasteEvent(PasteEvent(pasted))
            awaitSnapshot()

            assertEquals(expected, state.value.text)
            assertEquals(expected.length, state.value.cursorOffset)
        }
    }

    test("standalone Shift does not insert its Kitty functional key codepoint") {
        val state = TextInputState()

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(value = state.value, width = 80),
                    autoFocus = true,
                )
            }

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 57441,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            sendKeyEvent(KeyboardEvent(codepoint = 'x'.code))
            awaitSnapshot()

            assertEquals(TextInputValue(text = "x", cursorOffset = 1), state.value)
        }
    }

    test("Caps Lock does not insert its Kitty functional key codepoint") {
        val state = TextInputState()

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(value = state.value, width = 80),
                    autoFocus = true,
                )
            }

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 57358,
                    modifiers = KeyboardEvent.ModifierCapsLock,
                ),
            )
            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'X'.code,
                    modifiers = KeyboardEvent.ModifierCapsLock,
                ),
            )
            awaitSnapshot()

            assertEquals(TextInputValue(text = "X", cursorOffset = 1), state.value)
        }
    }

    test("editing notifies a caller that owns the draft") {
        val state = TextInputState()
        var observed: TextInputValue? = null

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(value = state.value, width = 80),
                    autoFocus = true,
                    onValueChanged = { value -> observed = value },
                )
            }

            sendPasteEvent(PasteEvent("draft"))
            awaitSnapshot()

            assertEquals(state.value, observed)
        }
    }

    test("editing preserves Unicode-scalar cursor boundaries") {
        val value = TextInputValue(text = "a😀b", cursorOffset = 3)

        assertEquals(
            TextInputValue(text = "a😀b", cursorOffset = 1),
            TextInputEdit.MoveCursorLeft.applyTo(value),
        )
        assertEquals(
            TextInputValue(text = "ab", cursorOffset = 1),
            TextInputEdit.DeleteBeforeCursor.applyTo(value),
        )
    }

    test("state owns edits and resets the draft") {
        val state = TextInputState(TextInputValue("a", cursorOffset = 1))

        assertEquals(true, state.edit(TextInputEdit.Insert("😀")))
        assertEquals(TextInputValue("a😀", cursorOffset = 3), state.value)

        state.reset()

        assertEquals(TextInputValue(), state.value)
    }

    test("layout accepts caller-owned line prefixes") {
        val layout = TextInputLayout.create(
            value = TextInputValue("first\nsecond"),
            width = 20,
            firstLinePrefix = "> ",
            continuationLinePrefix = "  ",
        )

        assertEquals("> first\n  second", layout.renderedText)
        assertEquals(2, layout.rowCount)
    }
}
