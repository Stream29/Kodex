package io.github.stream29.kodex.utils.externalurl

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val externalUrlTest by testSuite {
    for ((name, url) in listOf("empty" to "", "spaces" to "  ", "whitespace" to "\t\r\n")) {
        test("$name URL fails before invoking the client opener") {
            assertEquals(
                OpenExternalUrlResult.Failed("The URL must not be blank."),
                openExternalUrl(url),
            )
        }
    }
}
