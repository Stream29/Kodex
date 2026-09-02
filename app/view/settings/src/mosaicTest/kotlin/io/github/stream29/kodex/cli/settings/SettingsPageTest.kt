package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.app.settings.contract.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPageTest {
    @Test
    fun navigationOrderAndLabelsAreStable() {
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
