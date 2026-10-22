package io.github.stream29.kodex.tool.currenttime

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val NamespaceDescription: String = CurrentTimeTools.NamespaceDescription
private const val Description: String = CurrentTimeTools.Description

val currentTimeContractConstantsTest by testSuite {
    test("tool descriptions remain compile-time constants") {
        assertEquals("Tools for reading and waiting on time.", NamespaceDescription)
        assertEquals("Return the current time in UTC.", Description)
    }
}
