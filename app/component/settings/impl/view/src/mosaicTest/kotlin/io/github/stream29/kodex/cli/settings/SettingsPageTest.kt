package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.app.settings.contract.SettingsPage
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val settingsPageTest by testSuite {
    test("navigationOrderAndLabelsAreStable") {
        assertEquals(
            listOf(
                "General",
                "Context sources",
                "OpenAI",
                "MCP",
                "Hooks",
                "Current session",
                "New session",
            ),
            SettingsPage.entries.map { page -> page.settingsLabel() },
        )
    }
}
