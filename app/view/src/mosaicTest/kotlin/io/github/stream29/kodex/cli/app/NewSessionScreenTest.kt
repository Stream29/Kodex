package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

val newSessionScreenTest by testSuite {
    test("unmounting and returning retains the singleton Composer cursor and cwd") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession()
            draft.composer.update("not submitted", 4)
            val cwd = Path(fixture.rpc.root, "retained-cwd")
            draft.updateWorkingDirectory(cwd)
            draft.settings.first { it.cwd == cwd }
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    if (visible) {
                        NewSessionScreen(
                            viewModel = draft,
                            columns = 40,
                            rows = 8,
                            newLineKey = NewLineKey.ShiftEnter,
                            statusBarRows = 0,
                            onSubmit = {},
                            statusBar = {},
                        )
                    }
                }
                visible = false
                awaitSnapshot()
                assertEquals("not submitted", draft.composer.state.value.text)
                assertEquals(
                    io.github.stream29.kodex.app.agent.contract.ComposerLifecycle.Open,
                    draft.composer.state.value.lifecycle,
                )
                visible = true
                val snapshot = awaitSnapshot()
                assertContains(snapshot, "not submitted")
                assertSame(draft, fixture.newSession())
                assertEquals(4, draft.composer.state.value.cursorOffset)
                assertEquals(cwd, draft.settings.value.cwd)
                sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                awaitSnapshot()
                assertEquals("not Xsubmitted", draft.composer.state.value.text)
                assertEquals(5, draft.composer.state.value.cursorOffset)
            }
        } finally {
            fixture.close()
        }
    }
    test("emptySessionDoesNotRenderCreationInstructions") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    NewSessionContent(
                        composerViewModel = composer,
                        columns = 40,
                        rows = 8,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {},
                    )
                }

                assertFalse("Enter a prompt to create a session" in snapshot, snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    test("longComposerUsesItsBoundedRowsAndKeepsTheCursorTailVisible") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer
            val text = "one\ntwo\nthree\nfour"
            composer.update(text = text, cursorOffset = text.length)

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    NewSessionContent(
                        composerViewModel = composer,
                        columns = 10,
                        rows = 4,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {},
                    )
                }

                assertEquals(
                    "----------\n  two\n  three\n  four",
                    snapshot,
                )
            }
        } finally {
            fixture.close()
        }
    }

    test("submitCallbackRetainsTheExactComposerRevision") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val prompt = "Keep this first prompt."
            val composer = fixture.newSession().composer
            var submittedRevision: Long? = null

            runMosaicTest {
                setContentAndSnapshot {
                    var showNewSession by remember { mutableStateOf(true) }
                    if (showNewSession) {
                        NewSessionContent(
                            composerViewModel = composer,
                            columns = 80,
                            rows = 23,
                            newLineKey = NewLineKey.ShiftEnter,
                            onSubmit = { revision ->
                                submittedRevision = revision
                                showNewSession = false
                            },
                        )
                    } else {
                        Text("Root session")
                    }
                }

                sendPasteEvent(PasteEvent(prompt))
                awaitSnapshot()
                assertEquals(prompt, composer.state.value.text)

                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                assertEquals(1L, submittedRevision)
            }
        } finally {
            fixture.close()
        }
    }

    test("back to back input and send confirm revision despite a stale collected projection") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer
            val staleProjection = object : ComposerViewModel by composer {
                override val state = MutableStateFlow(composer.state.value)
            }
            var submittedRevision: Long? = null
            var submittedText: String? = null
            runMosaicTest {
                setContentAndSnapshot {
                    NewSessionContent(
                        composerViewModel = staleProjection,
                        columns = 40, rows = 8, newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = { revision ->
                            submittedRevision = revision
                            submittedText = composer.state.value.text
                            composer.update("later draft")
                        },
                    )
                }
                sendPasteEvent(PasteEvent("  exact input \n "))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals("  exact input \n ", submittedText)
                assertEquals(1L, submittedRevision)
                assertEquals("later draft", composer.state.value.text)
                assertEquals(2L, composer.state.value.revision)
            }
        } finally {
            fixture.close()
        }
    }

    for (text in listOf("", "   ", "\n", " \n first prompt \n ")) {
        test("New Session send carries raw text ${text.toCharArray().map { it.code }} without blank filtering") {
            val fixture = SessionViewModelTestFixture.create(this)
            try {
                val composer = fixture.newSession().composer
                composer.update(text)
                var revision: Long? = null
                var captured: String? = null
                var submissions by mutableStateOf(0)
                runMosaicTest {
                    setContentAndSnapshot {
                        Column {
                            NewSessionContent(
                                composer, 40, 8, NewLineKey.ShiftEnter,
                                onSubmit = {
                                    revision = it
                                    captured = composer.state.value.text
                                    submissions++
                                },
                            )
                            Text("submissions=$submissions")
                        }
                    }
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    awaitSnapshot()
                }
                assertEquals(text, captured)
                assertEquals(composer.state.value.revision, revision)
                assertEquals(1, submissions)
            } finally {
                fixture.close()
            }
        }
    }

    test("successful submit remounts the same cleared singleton and rejects a late revision") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession()
            val composer = draft.composer
            val initialCwd = draft.settings.value.cwd
            draft.updateWorkingDirectory(Path(fixture.rpc.root, "submitted-cwd"))
            val revision = composer.update("first")
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    if (visible) NewSessionScreen(
                        draft, 40, 8, NewLineKey.ShiftEnter, 0, {}, {},
                    ) else Text("persisted Session")
                }
                visible = false
                awaitSnapshot()
                assertNotNull(draft.submit(revision))
                draft.settings.first { it.cwd == initialCwd }
                assertSame(draft, fixture.newSession())
                assertSame(composer, draft.composer)
                assertEquals("", composer.state.value.text)
                assertEquals(0, composer.state.value.cursorOffset)
                visible = true
                val reset = awaitSnapshot()
                assertFalse("first" in reset, reset)
                sendPasteEvent(PasteEvent("second draft"))
                awaitSnapshot()
                assertNull(draft.submit(revision))
                assertEquals("second draft", composer.state.value.text)
                assertEquals(12, composer.state.value.cursorOffset)
            }
        } finally {
            fixture.close()
        }
    }

    test("composerStateEchoPreservesTheFrontendCursorAndHistory") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer

            runMosaicTest {
                setContentAndSnapshot {
                    NewSessionContent(
                        composerViewModel = composer,
                        columns = 20,
                        rows = 8,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {},
                    )
                }

                sendPasteEvent(PasteEvent("abc"))
                awaitSnapshot()
                repeat(2) {
                    sendKeyEvent(
                        KeyboardEvent(
                            codepoint = KeyboardEvent.Left,
                        ),
                    )
                    awaitSnapshot()
                }
                sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                awaitSnapshot()

                assertEquals("aXbc", composer.state.value.text)
                assertEquals(2, composer.state.value.cursorOffset)

                sendKeyEvent(
                    KeyboardEvent(
                        codepoint = 'z'.code,
                        modifiers = KeyboardEvent.ModifierCtrl,
                    ),
                )
                awaitSnapshot()
                assertEquals("abc", composer.state.value.text)
                assertEquals(1, composer.state.value.cursorOffset)

                sendKeyEvent(KeyboardEvent(codepoint = 'Y'.code))
                awaitSnapshot()
                assertEquals("aYbc", composer.state.value.text)

                composer.update(text = "external", cursorOffset = 8)
                awaitSnapshot()
                sendKeyEvent(
                    KeyboardEvent(
                        codepoint = 'z'.code,
                        modifiers = KeyboardEvent.ModifierCtrl,
                    ),
                )
                awaitSnapshot()
                assertEquals("external", composer.state.value.text)
                assertEquals(8, composer.state.value.cursorOffset)
            }
        } finally {
            fixture.close()
        }
    }
}
