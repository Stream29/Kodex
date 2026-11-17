package io.github.stream29.kodex.app.settings.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import kotlin.test.*

val newSessionDefaultsContractTest by testSuite {
    test("state enforces revision and options without inventing another canonical defaults value") {
        val settings = KodexNewSessionSettings()
        assertFailsWith<IllegalArgumentException> { NewSessionSettingsState(-1, settings, listOf(settings.model)) }
        assertFailsWith<IllegalArgumentException> { NewSessionSettingsState(0, settings, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            NewSessionSettingsState(0, settings, listOf(settings.model, settings.model))
        }
        val valid = NewSessionSettingsState(0, settings, listOf(settings.model))
        assertTrue(valid.active)
        assertEquals(settings, valid.copy(active = false).settings)
    }
}
