package io.github.stream29.codex.lite.tool.applypatch

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FreeformTool
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue



val applyPatchToolsTest by testSuite {
    test("spec is freeform grammar tool") {
        val spec = assertIs<FreeformTool>(ApplyPatchTools.spec)

        assertEquals("apply_patch", spec.name)
        assertEquals("grammar", spec.format.type)
        assertEquals("lark", spec.format.syntax)
        assertTrue(spec.format.definition.contains("begin_patch"))
        assertTrue(spec.format.definition.contains("*** Update File: "))
    }
}
