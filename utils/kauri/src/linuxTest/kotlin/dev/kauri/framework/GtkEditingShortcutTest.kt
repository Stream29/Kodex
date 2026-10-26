package dev.kauri.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GtkEditingShortcutTest {
    @Test fun matchesUndoAndBothRedoChordsWithoutLoadingGtk() {
        assertEquals(GtkEditingCommand.Undo, gtkEditingShortcut('z'.code.toUInt(), 4u))
        assertEquals(GtkEditingCommand.Redo, gtkEditingShortcut('Z'.code.toUInt(), 5u))
        assertEquals(GtkEditingCommand.Redo, gtkEditingShortcut('y'.code.toUInt(), 4u))
        assertEquals(GtkEditingCommand.Undo, gtkEditingShortcut('Z'.code.toUInt(), 6u))
        assertEquals(GtkEditingCommand.Redo, gtkEditingShortcut('z'.code.toUInt(), 21u))
    }

    @Test fun leavesPlainTypingOtherCommandsAndModifiedShortcutsToTheirOwners() {
        listOf(0u, 1u, 8u, 12u, 36u, 68u, 132u, (1u shl 26) or 4u).forEach {
            assertNull(gtkEditingShortcut('z'.code.toUInt(), it))
        }
        assertNull(gtkEditingShortcut('v'.code.toUInt(), 4u))
        assertNull(gtkEditingShortcut('y'.code.toUInt(), 5u))
        assertNull(gtkEditingShortcut(0xff1bu, 4u))
    }
}
