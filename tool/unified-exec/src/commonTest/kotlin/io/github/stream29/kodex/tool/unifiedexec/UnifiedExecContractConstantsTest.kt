package io.github.stream29.kodex.tool.unifiedexec

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val ExecCommandName: String = UnifiedExecTools.ExecCommandName
private const val ExecCommandDescription: String = UnifiedExecTools.ExecCommandDescription

val unifiedExecContractConstantsTest by testSuite {
    test("tool name, description, and retention limit remain compile-time constants") {
        assertEquals("exec_command", ExecCommandName)
        assertEquals(
            "Runs a command in a PTY, returning output or a session ID for ongoing interaction.",
            ExecCommandDescription,
        )
        assertEquals(600_000L, UnifiedExecCompletedSessionRetentionMillis)
    }
}
