package io.github.stream29.kodex.tool.webrun

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val NamespaceDescription: String = WebRunTools.NamespaceDescription

val webRunContractConstantsTest by testSuite {
    test("namespace description remains a compile-time constant") {
        assertEquals("Tools in the web namespace.", NamespaceDescription)
    }
}
