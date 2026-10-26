package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

val historyFormattingTest by testSuite {
    test("elapsed display rounds ties away from zero and preserves infinity") {
        assertEquals(" +1ms", 500.microseconds.historyElapsedSuffix())
        assertEquals(" +-1ms", (-500).microseconds.historyElapsedSuffix())
        assertEquals(" +2ms", 1_500.microseconds.historyElapsedSuffix())
        assertEquals(" +-2ms", (-1_500).microseconds.historyElapsedSuffix())
        assertEquals(" +Infinity", Duration.INFINITE.historyElapsedSuffix())
    }
    test("recorded command context omits known defaults but preserves explicit zero and whitespace") {
        assertEquals("exec_command; yield_time_ms=10000", ExecCommandArguments("pwd", yieldTimeMillis = 10_000).expandedCommandContext())
        assertEquals("write_stdin; yield_time_ms=250", WriteStdinArguments(7, yieldTimeMillis = 250).expandedCommandContext())
        assertEquals(
            "exec_command; cwd= ; tty=true; yield_time_ms=0; max_output_tokens=0",
            ExecCommandArguments("pwd", workdir = " ", tty = true, yieldTimeMillis = 0, maxOutputTokens = 0).expandedCommandContext(),
        )
        assertEquals("tool; zero=0; false=false; empty=", compactToolLine("tool", "zero" to 0, "false" to false, "empty" to "", "absent" to null))
    }
}
