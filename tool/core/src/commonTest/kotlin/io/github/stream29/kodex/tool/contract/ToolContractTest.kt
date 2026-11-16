package io.github.stream29.kodex.tool.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

val toolContractTest by testSuite {
    test("tool name renders plain and namespaced names and distinguishes namespaces") {
        assertEquals("apply_patch", ToolName.plain("apply_patch").toString())
        assertEquals("web.run", ToolName(name = "run", namespace = "web").toString())
        assertNotEquals(ToolName.plain("view_image"), ToolName(name = "view_image", namespace = "image_gen"))
        assertFailsWith<IllegalArgumentException> { ToolName.plain(" ") }
        assertFailsWith<IllegalArgumentException> { ToolName(name = "run", namespace = "") }
    }
}
