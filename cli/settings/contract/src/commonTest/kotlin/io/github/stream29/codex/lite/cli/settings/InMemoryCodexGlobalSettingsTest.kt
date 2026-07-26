package io.github.stream29.codex.lite.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val inMemoryCodexGlobalSettingsTest by testSuite {
    test("publishes a complete updated settings snapshot") {
        val initial = CodexGlobalSettings(codexHome = Path("/home/test/.codex"))
        val settings = InMemoryCodexGlobalSettings(initial)
        val shell = Shell(ShellType.Zsh, Path("/bin/zsh"))

        assertEquals(initial, settings.settings.value)

        val updated = settings.update {
            it.copy(
                codexHome = Path("/workspace/.codex"),
                shell = shell,
                newLineKey = NewLineKey.Enter,
            )
        }

        assertEquals(updated, settings.settings.value)
        assertEquals(Path("/workspace/.codex"), updated.codexHome)
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
