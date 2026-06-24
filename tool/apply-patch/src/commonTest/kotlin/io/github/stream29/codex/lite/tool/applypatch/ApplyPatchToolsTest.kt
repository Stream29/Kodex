package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.tool.contract.FreeformTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApplyPatchToolsTest {
    @Test
    fun specIsFreeformGrammarTool() {
        val spec = assertIs<FreeformTool>(ApplyPatchTools.spec)

        assertEquals("apply_patch", spec.name)
        assertEquals("grammar", spec.format.type)
        assertEquals("lark", spec.format.syntax)
        assertTrue(spec.format.definition.contains("begin_patch"))
        assertTrue(spec.format.definition.contains("*** Update File: "))
    }
}
