package io.github.stream29.kodex.utils.externalurl

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val externalUrlTest by testSuite {
    test("blank URL fails before invoking the host opener") {
        assertEquals(
            OpenExternalUrlResult.Failed("The URL must not be blank."),
            openExternalUrl("  "),
        )
    }
}
