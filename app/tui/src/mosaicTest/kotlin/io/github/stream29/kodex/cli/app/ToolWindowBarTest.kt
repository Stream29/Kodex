package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.ToolWindowId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val toolWindowBarTest by testSuite {
    test("uses full labels at forty columns and keeps all targets reachable down to three columns") {
        assertEquals(
            listOf("[Sessions]", "[History]", "[Terminal]"),
            toolWindowBarButtons(40, null, null).map { it.label },
        )
        assertEquals(
            listOf("[Sess]", "[Idx]", "[Term]"),
            toolWindowBarButtons(39, null, null).map { it.label },
        )
        for (columns in 0..50) {
            val buttons = toolWindowBarButtons(columns, ToolWindowId.Sessions, ToolWindowId.TerminalSessions)
            assertTrue(buttons.sumOf { it.label.length } <= columns, "columns=$columns, buttons=$buttons")
            if (columns >= 3) assertEquals(ToolWindowId.entries.toList(), buttons.map { it.id })
        }
        assertEquals(
            listOf(ToolWindowId.Sessions, ToolWindowId.TerminalSessions),
            toolWindowBarButtons(2, ToolWindowId.Sessions, ToolWindowId.TerminalSessions).map { it.id },
        )
    }

    test("mouse and keyboard target the visible toggles at opposite edges") {
        var toggles by mutableStateOf(emptyList<ToolWindowId>())
        runMosaicTest {
            val screen = setContentAndSnapshot {
                Column {
                    ToolWindowBar(
                        columns = 39,
                        activeLeft = ToolWindowId.Sessions,
                        activeRight = ToolWindowId.TerminalSessions,
                        onToggle = { toggles += it },
                    )
                    Text("Toggles: ${toggles.size}")
                }
            }
            val bar = screen.lineSequence().first().trimEnd()
            assertTrue(bar.startsWith("[Sess]"), screen)
            assertTrue(bar.endsWith("[Idx] [Term]"), screen)

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(38, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(38, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(
                listOf(ToolWindowId.Sessions, ToolWindowId.TerminalSessions, ToolWindowId.TerminalSessions),
                toggles,
            )
        }
    }
}
