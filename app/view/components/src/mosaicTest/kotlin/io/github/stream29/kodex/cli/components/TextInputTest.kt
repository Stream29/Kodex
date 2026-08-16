package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    test("disabled input keeps its selection dimmed and inverted without accepting pointer edits") {
        val selected = TextInputValue(text = "locked", cursorOffset = 6, selectionAnchor = 0)
        val state = TextInputState(selected)

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val snapshot = setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(value = state.value, width = 80),
                    enabled = false,
                )
            }

            assertTrue(
                "\u001B[7m" in snapshot ||
                    "\u001B[2;7m" in snapshot ||
                    "\u001B[7;2m" in snapshot,
                snapshot,
            )
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            assertEquals(selected, state.value)
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

    test("unmodified vertical arrows leave the input only at visual boundaries") {
        val state = TextInputState(TextInputValue("abcdefgh", cursorOffset = 4))
        var focused = ""

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    Text(
                        value = "before",
                        modifier = Modifier
                            .onFocusChanged {
                                if (it == FocusState.Active) focused = "before"
                            }
                            .focusable(),
                    )
                    TextInput(
                        state = state,
                        layout = TextInputLayout.create(
                            value = state.value,
                            width = 3,
                            softWrap = true,
                        ),
                        modifier = Modifier.onFocusChanged {
                            if (it == FocusState.Active) focused = "input"
                        },
                        autoFocus = true,
                    )
                    Text(
                        value = "after",
                        modifier = Modifier
                            .onFocusChanged {
                                if (it == FocusState.Active) focused = "after"
                            }
                            .focusable(),
                    )
                }
            }
            assertEquals("input", focused)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Up))
            awaitSnapshot()
            assertEquals("input", focused)
            assertEquals(1, state.value.cursorOffset)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Up))
            awaitSnapshot()
            assertEquals("before", focused)
            assertEquals(1, state.value.cursorOffset)

            sendKeyEvent(KeyboardEvent(codepoint = 9))
            awaitSnapshot()
            assertEquals("input", focused)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            awaitSnapshot()
            assertEquals("input", focused)
            assertEquals(4, state.value.cursorOffset)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            awaitSnapshot()
            assertEquals("input", focused)
            assertEquals(7, state.value.cursorOffset)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            awaitSnapshot()
            assertEquals("after", focused)
            assertEquals(7, state.value.cursorOffset)
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
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        assertEquals(0, state.scrollOffset)
    }

    test("selection-aware edits replace the complete selected range") {
        val selection = TextInputValue(
            text = "abcdef",
            cursorOffset = 5,
            selectionAnchor = 1,
        )

        assertEquals(
            TextInputValue(text = "aXf", cursorOffset = 2),
            TextInputEdit.Insert("X").applyTo(selection),
        )
        assertEquals(
            TextInputValue(text = "af", cursorOffset = 1),
            TextInputEdit.DeleteBeforeCursor.applyTo(selection),
        )
        assertEquals(
            TextInputValue(text = "af", cursorOffset = 1),
            TextInputEdit.DeleteAtCursor.applyTo(selection),
        )
    }

    test("movement extends, reverses, and collapses a selection") {
        val state = TextInputState(TextInputValue("abcdef", cursorOffset = 2))
        var layout = TextInputLayout.create(
            value = state.value,
            width = 3,
            softWrap = true,
        )

        assertTrue(state.moveCursor(TextInputMovement.Down, layout = layout))
        assertEquals(TextInputValue("abcdef", cursorOffset = 5), state.value)

        layout = TextInputLayout.create(value = state.value, width = 3, softWrap = true)
        assertTrue(
            state.moveCursor(
                movement = TextInputMovement.Up,
                extendSelection = true,
                layout = layout,
            ),
        )
        assertEquals(
            TextInputValue("abcdef", cursorOffset = 2, selectionAnchor = 5),
            state.value,
        )

        assertTrue(state.moveCursor(TextInputMovement.Right))
        assertEquals(TextInputValue("abcdef", cursorOffset = 5), state.value)
    }

    test("vertical movement preserves its terminal column across short, wide, and empty lines") {
        val wideState = TextInputState(TextInputValue("a界b\nx\nabcdef", cursorOffset = 2))
        var layout = TextInputLayout.create(value = wideState.value, width = 10, softWrap = true)

        assertTrue(wideState.moveCursor(TextInputMovement.Down, layout = layout))
        assertEquals(5, wideState.value.cursorOffset)
        layout = TextInputLayout.create(value = wideState.value, width = 10, softWrap = true)
        assertTrue(wideState.moveCursor(TextInputMovement.Down, layout = layout))
        assertEquals(9, wideState.value.cursorOffset)
        layout = TextInputLayout.create(value = wideState.value, width = 10, softWrap = true)
        assertTrue(wideState.moveCursor(TextInputMovement.Up, layout = layout))
        assertEquals(5, wideState.value.cursorOffset)

        val emptyState = TextInputState(TextInputValue("abc\n\nabcdef", cursorOffset = 3))
        layout = TextInputLayout.create(value = emptyState.value, width = 10, softWrap = true)
        assertTrue(emptyState.moveCursor(TextInputMovement.Down, layout = layout))
        assertEquals(4, emptyState.value.cursorOffset)
        layout = TextInputLayout.create(value = emptyState.value, width = 10, softWrap = true)
        assertTrue(emptyState.moveCursor(TextInputMovement.Down, layout = layout))
        assertEquals(8, emptyState.value.cursorOffset)
    }

    test("hard-line and document boundary movements remain distinct") {
        val state = TextInputState(TextInputValue("one\ntwo", cursorOffset = 6))

        assertTrue(state.moveCursor(TextInputMovement.LineStart))
        assertEquals(TextInputValue("one\ntwo", cursorOffset = 4), state.value)
        assertTrue(
            state.moveCursor(
                movement = TextInputMovement.LineEnd,
                extendSelection = true,
            ),
        )
        assertEquals(
            TextInputValue("one\ntwo", cursorOffset = 7, selectionAnchor = 4),
            state.value,
        )
        assertTrue(state.moveCursor(TextInputMovement.DocumentStart))
        assertEquals(TextInputValue("one\ntwo", cursorOffset = 0), state.value)
    }

    test("Ctrl+W word grouping crosses whitespace and punctuation groups") {
        var value = TextInputValue("foo/bar")

        value = TextInputEdit.DeletePreviousWord.applyTo(value)
        assertEquals(TextInputValue("foo/"), value)
        value = TextInputEdit.DeletePreviousWord.applyTo(value)
        assertEquals(TextInputValue("foo"), value)
        value = TextInputEdit.DeletePreviousWord.applyTo(value)
        assertEquals(TextInputValue(), value)

        assertEquals(
            TextInputValue(),
            TextInputEdit.DeletePreviousWord.applyTo(TextInputValue("previous \n ")),
        )
        assertEquals(
            TextInputValue(),
            TextInputEdit.DeletePreviousWord.applyTo(TextInputValue("词组_42")),
        )
    }

    test("undo groups typing and same-direction deletion but preserves atomic inserts") {
        val state = TextInputState()
        state.edit(TextInputEdit.Insert("a", mergeWithPrevious = true))
        state.edit(TextInputEdit.Insert("b", mergeWithPrevious = true))
        state.edit(TextInputEdit.Insert("c", mergeWithPrevious = true))

        assertTrue(state.undo())
        assertEquals(TextInputValue(), state.value)
        assertTrue(state.redo())
        assertEquals(TextInputValue("abc"), state.value)

        state.edit(TextInputEdit.DeleteBeforeCursor)
        state.edit(TextInputEdit.DeleteBeforeCursor)
        assertEquals(TextInputValue("a"), state.value)
        assertTrue(state.undo())
        assertEquals(TextInputValue("abc"), state.value)

        state.reset()
        state.edit(TextInputEdit.Insert("paste"))
        state.edit(TextInputEdit.Insert("\n"))
        assertTrue(state.undo())
        assertEquals(TextInputValue("paste"), state.value)

        state.reset(TextInputValue("abc", cursorOffset = 0))
        state.edit(TextInputEdit.DeleteAtCursor)
        state.edit(TextInputEdit.DeleteAtCursor)
        assertEquals(TextInputValue("c", cursorOffset = 0), state.value)
        assertTrue(state.undo())
        assertEquals(TextInputValue("abc", cursorOffset = 0), state.value)
    }

    test("branch edits clear redo and history remains bounded") {
        val state = TextInputState()
        state.edit(TextInputEdit.Insert("a"))
        state.edit(TextInputEdit.Insert("b"))
        assertTrue(state.undo())
        assertTrue(state.canRedo)

        state.edit(TextInputEdit.Insert("x"))
        assertFalse(state.canRedo)
        assertEquals(TextInputValue("ax"), state.value)

        state.reset()
        repeat(101) {
            state.edit(TextInputEdit.Insert("x"))
        }
        repeat(100) {
            assertTrue(state.undo())
        }
        assertEquals(TextInputValue("x"), state.value)
        assertFalse(state.undo())
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

    test("soft wrap preserves source rows, prefixes, and cursor mapping") {
        val layout = TextInputLayout.create(
            value = TextInputValue("abcde"),
            width = 4,
            firstLinePrefix = "> ",
            continuationLinePrefix = "  ",
            softWrap = true,
        )

        assertEquals(listOf("> ab", "  cd", "  e"), layout.lines)
        assertEquals(2, layout.cursorRow)
        assertEquals(3, layout.cursorColumn)
        assertEquals(2, layout.sourceOffsetAt(column = 2, row = 1))
        assertEquals(3, layout.sourceOffsetAt(column = 3, row = 1))

        val viewport = layout.withViewportRows(2)
        assertEquals(2, viewport.visibleRowCount)
        assertEquals("  cd\n  e", viewport.visibleText(firstRow = 1).text)
    }

    test("soft wrap is grapheme-safe and makes progress at narrow widths") {
        assertEquals(
            listOf("a界", "b"),
            TextInputLayout.create(
                value = TextInputValue("a界b"),
                width = 3,
                softWrap = true,
            ).lines,
        )
        assertEquals(
            listOf("e\u0301", "x", ""),
            TextInputLayout.create(
                value = TextInputValue("e\u0301x"),
                width = 1,
                softWrap = true,
            ).lines,
        )
        assertEquals(
            listOf("界", "a", ""),
            TextInputLayout.create(
                value = TextInputValue("界a"),
                width = 1,
                softWrap = true,
            ).lines,
        )
        assertEquals(
            listOf("界", ""),
            TextInputLayout.create(
                value = TextInputValue("界"),
                width = 1,
                softWrap = true,
            ).lines,
        )
    }

    test("soft wrap preserves spaces, empty hard lines, and exact hard-line boundaries") {
        assertEquals(
            listOf("ab  ", "cd", "", "xy"),
            TextInputLayout.create(
                value = TextInputValue("ab  cd\n\nxy"),
                width = 4,
                softWrap = true,
            ).lines,
        )

        val beforeNewline = TextInputLayout.create(
            value = TextInputValue("abcd\nx", cursorOffset = 4),
            width = 4,
            softWrap = true,
        )
        assertEquals(listOf("abcd", "x"), beforeNewline.lines)
        assertEquals(1, beforeNewline.cursorRow)
        assertEquals(0, beforeNewline.cursorColumn)

        assertEquals(
            listOf("abcd", ""),
            TextInputLayout.create(
                value = TextInputValue("abcd"),
                width = 4,
                softWrap = true,
            ).lines,
        )
    }

    test("pointer mapping excludes prefixes and uses wide-cell boundaries") {
        val layout = TextInputLayout.create(
            value = TextInputValue("界a", cursorOffset = 0),
            width = 10,
            firstLinePrefix = "> ",
            softWrap = true,
        )

        assertEquals(0, layout.sourceOffsetAt(column = 0, row = 0))
        assertEquals(0, layout.sourceOffsetAt(column = 2, row = 0))
        assertEquals(1, layout.sourceOffsetAt(column = 3, row = 0))
        assertEquals(1, layout.sourceOffsetAt(column = 4, row = 0))
        assertEquals(2, layout.sourceOffsetAt(column = 5, row = 0))
    }

    test("viewport reflow clamps scrolling and keeps the active cursor visible") {
        val state = TextInputState(TextInputValue("abcdefghijkl"))
        var layout = TextInputLayout.create(
            value = state.value,
            width = 4,
            softWrap = true,
        ).withViewportRows(2)
        var resolved = state.resolvedScrollOffset(layout)
        state.commitViewport(layout, resolved)
        assertTrue(layout.cursorRow in resolved until resolved + layout.visibleRowCount)

        assertTrue(state.scrollBy(-2, layout) < 0)
        layout = TextInputLayout.create(
            value = state.value,
            width = 2,
            softWrap = true,
        ).withViewportRows(3)
        resolved = state.resolvedScrollOffset(layout)
        state.commitViewport(layout, resolved)

        assertTrue(layout.cursorRow in resolved until resolved + layout.visibleRowCount)
        assertTrue(state.scrollOffset <= layout.rowCount - layout.visibleRowCount)
    }

    test("editing, movement, undo, and reset reestablish cursor visibility") {
        val initial = "a\nb\nc\nd\ne"
        val state = TextInputState(TextInputValue(initial))

        fun layout(): TextInputLayout = TextInputLayout.create(
            value = state.value,
            width = 10,
            softWrap = true,
        ).withViewportRows(2)

        fun assertCursorVisible() {
            val current = layout()
            val firstRow = state.resolvedScrollOffset(current)
            assertTrue(current.cursorRow in firstRow until firstRow + current.visibleRowCount)
            state.commitViewport(current, firstRow)
        }

        assertCursorVisible()
        assertTrue(state.scrollBy(-10, layout()) < 0)
        assertTrue(state.edit(TextInputEdit.DeleteBeforeCursor))
        assertCursorVisible()

        assertTrue(state.scrollBy(-10, layout()) < 0)
        assertTrue(state.undo())
        assertCursorVisible()

        assertTrue(state.scrollBy(-10, layout()) < 0)
        assertTrue(state.moveCursor(TextInputMovement.Left))
        assertCursorVisible()

        state.reset(TextInputValue("a\nb"))
        assertCursorVisible()
        assertEquals(0, state.scrollOffset)
    }

    test("keyboard selection renders inverted and undo restores it") {
        val state = TextInputState(TextInputValue("abc", cursorOffset = 0))

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(value = state.value, width = 80),
                    autoFocus = true,
                )
            }

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.Right,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.Right,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            val selectedSnapshot = awaitSnapshot()
            assertEquals(
                TextInputValue("abc", cursorOffset = 2, selectionAnchor = 0),
                state.value,
            )
            assertTrue("\u001B[7m" in selectedSnapshot, selectedSnapshot)

            sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
            awaitSnapshot()
            assertEquals(TextInputValue("Xc", cursorOffset = 1), state.value)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'z'.code,
                    modifiers = KeyboardEvent.ModifierCtrl,
                ),
            )
            awaitSnapshot()
            assertEquals(
                TextInputValue("abc", cursorOffset = 2, selectionAnchor = 0),
                state.value,
            )
        }
    }

    test("primary-pointer drag selects across visual rows") {
        val state = TextInputState(TextInputValue("abcdef", cursorOffset = 0))

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(
                        value = state.value,
                        width = 3,
                        softWrap = true,
                    ).withViewportRows(2),
                    autoFocus = true,
                )
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(2, 1, MouseEvent.Type.Drag, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(2, 1, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(
                TextInputValue("abcdef", cursorOffset = 5, selectionAnchor = 1),
                state.value,
            )

            sendMouseEvent(MouseEvent(2, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Drag, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            assertEquals(
                TextInputValue("abcdef", cursorOffset = 1, selectionAnchor = 5),
                state.value,
            )

            val beforeShiftClick = state.value
            sendMouseEvent(
                MouseEvent(
                    x = 0,
                    y = 0,
                    type = MouseEvent.Type.Press,
                    button = MouseEvent.Button.Left,
                    shift = true,
                ),
            )
            assertEquals(beforeShiftClick, state.value)
        }
    }

    test("pointer rows include the current viewport offset") {
        val text = "one\ntwo\nthree"
        val state = TextInputState(TextInputValue(text))

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(
                        value = state.value,
                        width = 20,
                    ).withViewportRows(2),
                    autoFocus = true,
                )
            }
            awaitSnapshot()
            assertEquals(1, state.scrollOffset)

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(TextInputValue(text, cursorOffset = 4), state.value)
        }
    }

    test("wheel scrolls the bounded viewport until the next cursor movement") {
        val text = "line1\nline2\nline3\nline4"
        val state = TextInputState(TextInputValue(text))

        runMosaicTest {
            setContentAndSnapshot {
                TextInput(
                    state = state,
                    layout = TextInputLayout.create(
                        value = state.value,
                        width = 20,
                    ).withViewportRows(2),
                    autoFocus = true,
                )
            }
            awaitSnapshot()
            assertEquals(2, state.scrollOffset)

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            awaitSnapshot()
            assertEquals(0, state.scrollOffset)
            assertEquals(text.length, state.value.cursorOffset)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Left))
            awaitSnapshot()
            assertEquals(2, state.scrollOffset)
        }
    }
}
