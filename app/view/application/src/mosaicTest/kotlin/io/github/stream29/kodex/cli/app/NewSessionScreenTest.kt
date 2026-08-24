package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NewSessionScreenTest {
    @Test
    fun emptySessionDoesNotRenderCreationInstructions() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession("New Session").composer

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

    @Test
    fun longComposerUsesItsBoundedRowsAndKeepsTheCursorTailVisible() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession("New Session").composer
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

    @Test
    fun submitCallbackRetainsTheExactComposerRevision() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val prompt = "Keep this first prompt."
            val composer = fixture.newSession("New Session").composer
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
                            onSubmit = {
                                submittedRevision = composer.state.value.revision
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
                assertEquals(1, submittedRevision)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun composerStateEchoPreservesTheFrontendCursorAndHistory() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession("New Session").composer

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
