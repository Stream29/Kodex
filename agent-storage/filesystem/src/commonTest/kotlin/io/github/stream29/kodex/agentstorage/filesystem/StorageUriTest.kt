package io.github.stream29.kodex.agentstorage.filesystem

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val uriFormatterTest by testSuite {
    test("formats Windows drive paths without encoding") {
        assertEquals(
            "file:///C:/Users/A B/#session?value=100%",
            formatWindowsStorageUri("""C:\Users\A B\#session?value=100%"""),
        )
    }

    test("formats Windows UNC paths without encoding") {
        assertEquals(
            "file://server/share/A B/#session?value=100%",
            formatWindowsStorageUri("""\\server\share\A B\#session?value=100%"""),
        )
    }

    test("removes an extended Windows path prefix") {
        assertEquals(
            "file:///C:/Users/session",
            formatWindowsStorageUri("""\\?\C:\Users\session"""),
        )
    }
}
