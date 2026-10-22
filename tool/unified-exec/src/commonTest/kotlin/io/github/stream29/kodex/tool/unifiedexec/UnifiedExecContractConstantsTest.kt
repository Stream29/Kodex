package io.github.stream29.kodex.tool.unifiedexec

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val ExecCommandName: String = UnifiedExecTools.ExecCommandName
private const val WriteStdinName: String = UnifiedExecTools.WriteStdinName
private const val ExecCommandDescription: String = UnifiedExecTools.ExecCommandDescription
private const val WriteStdinDescription: String = UnifiedExecTools.WriteStdinDescription

val unifiedExecContractConstantsTest by testSuite {
    test("tool names and descriptions remain compile-time constants") {
        assertEquals("exec_command", ExecCommandName)
        assertEquals("write_stdin", WriteStdinName)
        assertEquals(
            "Runs a command in a PTY, returning output or a session ID for ongoing interaction.",
            ExecCommandDescription,
        )
        assertEquals(
            "Writes characters to an existing unified exec session and returns recent output.",
            WriteStdinDescription,
        )
    }
}
