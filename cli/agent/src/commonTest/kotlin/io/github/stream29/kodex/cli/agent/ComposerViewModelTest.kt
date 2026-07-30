package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNull

val composerViewModelTest by testSuite {
    test("draft is independent and cleared when taken") {
        val composer = ComposerViewModel()

        composer.update(text = "draft", cursorOffset = 3)

        assertEquals(ComposerViewState(text = "draft", cursorOffset = 3, revision = 1), composer.state.value)
        assertEquals("draft", composer.takeText())
        assertEquals(ComposerViewState(revision = 2), composer.state.value)
    }

    test("blank draft is not submitted") {
        val composer = ComposerViewModel()

        composer.update(text = "  \n", cursorOffset = 3)

        assertNull(composer.takeText())
        assertEquals(ComposerViewState(text = "  \n", cursorOffset = 3, revision = 1), composer.state.value)
    }
}
