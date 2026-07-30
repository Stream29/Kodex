package io.github.stream29.kodex.tool.applypatch

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.FreeformTool
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue



val applyPatchToolsTest by testSuite {
    test("spec is freeform grammar tool") {
        val spec = assertIs<FreeformTool>(ApplyPatchTools.spec)

        assertEquals("apply_patch", spec.name)
        assertEquals(
            "Use the `apply_patch` tool to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON.",
            spec.description,
        )
        assertEquals("grammar", spec.format.type)
        assertEquals("lark", spec.format.syntax)
        assertTrue(spec.format.definition.contains("begin_patch"))
        assertTrue(spec.format.definition.contains("*** Update File: "))
    }
}
