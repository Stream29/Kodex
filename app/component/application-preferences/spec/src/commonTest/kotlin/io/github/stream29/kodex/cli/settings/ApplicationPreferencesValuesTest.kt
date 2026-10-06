package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalSerializationApi::class)
val applicationPreferencesValuesTest by testSuite {
    test("sidebars default to history index and terminal sessions with original standalone widths") {
        val sidebars = SidebarSettings()
        assertEquals(SidebarContent.HistoryIndex, sidebars.left)
        assertEquals(SidebarContent.TerminalSessions, sidebars.right)
        assertEquals(28, DefaultSidebarWidthColumns)
        assertEquals(4, MinimumSidebarWidthColumns)
        assertEquals(DefaultSidebarWidthColumns, sidebars.leftWidth)
        assertEquals(DefaultSidebarWidthColumns, sidebars.rightWidth)
    }

    test("both sidebar widths enforce the original minimum on construction and copy") {
        assertFailsWith<IllegalArgumentException> { SidebarSettings(leftWidth = 3) }
        assertFailsWith<IllegalArgumentException> { SidebarSettings(rightWidth = 3) }
        val smallest = SidebarSettings(leftWidth = 4, rightWidth = 4)
        assertEquals(4, smallest.leftWidth)
        assertEquals(4, smallest.rightWidth)
        assertFailsWith<IllegalArgumentException> { smallest.copy(leftWidth = -1) }
        assertFailsWith<IllegalArgumentException> { smallest.copy(rightWidth = -1) }
    }

    test("newline and submit keys form exactly two valid pairs") {
        assertEquals(listOf(NewLineKey.ShiftEnter, NewLineKey.Enter), NewLineKey.entries)
        assertEquals(SubmitKey.Enter, NewLineKey.ShiftEnter.submitKey)
        assertEquals(SubmitKey.CtrlEnter, NewLineKey.Enter.submitKey)
        assertEquals(NewLineKey.ShiftEnter, SubmitKey.Enter.newLineKey)
        assertEquals(NewLineKey.Enter, SubmitKey.CtrlEnter.newLineKey)
    }

    test("original preference descriptors and enum golden names are preserved") {
        val content = SidebarContent.serializer()
        val newline = NewLineKey.serializer()
        val submit = SubmitKey.serializer()
        assertEquals("io.github.stream29.kodex.cli.settings.SidebarContent", content.descriptor.serialName)
        assertEquals("io.github.stream29.kodex.cli.settings.NewLineKey", newline.descriptor.serialName)
        assertEquals("io.github.stream29.kodex.cli.settings.SubmitKey", submit.descriptor.serialName)
        for ((value, wire) in listOf(
            SidebarContent.None to "none",
            SidebarContent.TerminalSessions to "terminal_sessions",
            SidebarContent.HistoryIndex to "history_index",
        )) {
            assertEquals("\"$wire\"", Json.encodeToString(content, value))
            assertEquals(value, Json.decodeFromString(content, "\"$wire\""))
        }
        for ((value, wire) in listOf(NewLineKey.ShiftEnter to "shift_enter", NewLineKey.Enter to "enter")) {
            assertEquals("\"$wire\"", Json.encodeToString(newline, value))
            assertEquals(value, Json.decodeFromString(newline, "\"$wire\""))
        }
        for ((value, wire) in listOf(SubmitKey.Enter to "Enter", SubmitKey.CtrlEnter to "CtrlEnter")) {
            assertEquals("\"$wire\"", Json.encodeToString(submit, value))
            assertEquals(value, Json.decodeFromString(submit, "\"$wire\""))
        }
    }
}
