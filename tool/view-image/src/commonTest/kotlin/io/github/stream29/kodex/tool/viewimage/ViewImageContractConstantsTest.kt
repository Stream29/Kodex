package io.github.stream29.kodex.tool.viewimage

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val Name: String = ViewImageTools.Name
private const val Description: String = ViewImageTools.Description

val viewImageContractConstantsTest by testSuite {
    test("tool name and description remain compile-time constants") {
        assertEquals("view_image", Name)
        assertEquals(ViewImageTools.Description, Description)
    }
}
