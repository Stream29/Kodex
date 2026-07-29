package io.github.stream29.codex.lite.cli.components

import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val textInputTest by testSuite {
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
