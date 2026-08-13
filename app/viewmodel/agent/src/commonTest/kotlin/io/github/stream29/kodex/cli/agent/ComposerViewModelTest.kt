package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.ComposerState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val composerViewModelTest by testSuite {
    test("updates preserve revision CAS semantics") {
        val composer = DefaultComposerViewModelFactory.create()

        val revision = composer.update(text = "draft", cursorOffset = 3)

        assertEquals(1, revision)
        assertEquals(
            ComposerState(text = "draft", cursorOffset = 3, revision = 1),
            composer.state.value,
        )
        assertFalse(composer.clear(expectedRevision = 0))
        assertTrue(composer.clear(expectedRevision = revision))
        assertEquals(ComposerState(revision = 2), composer.state.value)
    }

    test("unchanged updates retain the current revision") {
        val composer = DefaultComposerViewModelFactory.create()
        val revision = composer.update(text = "draft", cursorOffset = 3)

        assertEquals(revision, composer.update(text = "draft", cursorOffset = 3))
        assertFalse(composer.clear(expectedRevision = revision - 1))
        assertEquals(
            ComposerState(text = "draft", cursorOffset = 3, revision = revision),
            composer.state.value,
        )
    }
}
