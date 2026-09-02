package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val inMemoryKodexGlobalSettingsTest by testSuite {
    test("sidebars default to history index and terminal sessions") {
        val sidebars = SidebarSettings()

        assertEquals(SidebarContent.HistoryIndex, sidebars.left)
        assertEquals(SidebarContent.TerminalSessions, sidebars.right)
    }

    test("publishes a complete updated settings snapshot") {
        val initial = KodexGlobalSettings()
        val settings = InMemoryKodexGlobalSettings(initial)
        val shell = Shell(ShellType.Zsh, Path("/bin/zsh"))

        assertEquals(initial, settings.settings.value)

        val updated = settings.update {
            it.copy(
                authSource = KodexAuthSource.Kodex,
                shell = shell,
                newLineKey = NewLineKey.Enter,
            )
        }

        assertEquals(updated, settings.settings.value)
        assertEquals(KodexAuthSource.Kodex, updated.authSource)
        assertEquals(shell, updated.shell)
        assertEquals(NewLineKey.Enter, updated.newLineKey)
        assertEquals(SubmitKey.CtrlEnter, updated.newLineKey.submitKey)
    }

    test("newline and submit keys form exactly two valid pairs") {
        assertEquals(listOf(NewLineKey.ShiftEnter, NewLineKey.Enter), NewLineKey.entries)
        assertEquals(SubmitKey.Enter, NewLineKey.ShiftEnter.submitKey)
        assertEquals(SubmitKey.CtrlEnter, NewLineKey.Enter.submitKey)
        assertEquals(NewLineKey.ShiftEnter, SubmitKey.Enter.newLineKey)
        assertEquals(NewLineKey.Enter, SubmitKey.CtrlEnter.newLineKey)
    }
}
