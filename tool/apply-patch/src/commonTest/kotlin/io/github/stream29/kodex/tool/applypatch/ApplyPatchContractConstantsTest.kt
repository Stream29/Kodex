package io.github.stream29.kodex.tool.applypatch

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val Name: String = ApplyPatchTools.Name

val applyPatchContractConstantsTest by testSuite {
    test("tool name remains a compile-time constant") {
        assertEquals("apply_patch", Name)
    }
}
