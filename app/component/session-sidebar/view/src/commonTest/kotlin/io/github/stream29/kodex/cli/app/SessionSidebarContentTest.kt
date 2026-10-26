package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlin.test.assertEquals

val sessionSidebarContentTest by testSuite {
    test("content choices preserve their established labels and order") {
        assertEquals(listOf("None", "Terminal sessions", "History Index"), SidebarContent.entries.map(::sidebarContentLabel))
    }
}
