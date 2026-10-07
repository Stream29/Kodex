package io.github.stream29.kodex.mcp.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val mcpModelToolNamesTest by testSuite {
    test("original route normalization is unchanged") {
        assertEquals("ABC_012", "ABC_012".toModelToolName())
        assertEquals("a_b", "a-b".toModelToolName())
        assertEquals("_", "".toModelToolName())
        assertEquals("__", "中文".toModelToolName())
        requireUniqueMcpModelNames(listOf("alpha-beta", "gamma", "ABC_012"), "server")
    }
    test("legal raw server and catalog names cannot collapse to one model route") {
        for (names in listOf(listOf("a-b", "a_b"), listOf("x-y", "x_y"), listOf("echo", "echo"))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                requireUniqueMcpModelNames(names, "tool")
            }
            assertTrue(failure.message.orEmpty().contains("Ambiguous MCP"))
            names.forEach { assertTrue(failure.message.orEmpty().contains(it)) }
        }
    }
}
