package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRenameEditorTest {
    @Test
    fun plainEnterSubmitsWithoutAnActionButton() = runTest {
        var submissions by mutableStateOf(0)
        val input = TextInputState(TextInputValue("Session title", "Session title".length))

        runMosaicTest {
            val initial = setContentAndSnapshot {
                Column {
                    SessionRenameEditor(
                        draftName = input.value.text,
                        input = input,
                        width = 40,
                        onDraftNameChanged = {},
                        onSubmit = { submissions += 1 },
                    )
                    Text("submissions=$submissions")
                }
            }
            assertTrue("Rename session" in initial, initial)
            assertFalse("[Rename]" in initial, initial)
            assertFalse("[Cancel]" in initial, initial)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 13,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            assertEquals(0, submissions)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val submitted = awaitSnapshot()
            assertTrue("submissions=1" in submitted, submitted)
        }

        assertEquals(1, submissions)
    }
}
