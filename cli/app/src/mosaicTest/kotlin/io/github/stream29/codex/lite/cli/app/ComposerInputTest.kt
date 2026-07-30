package io.github.stream29.codex.lite.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.codex.lite.cli.components.TextInputLayout
import io.github.stream29.codex.lite.cli.components.TextInputState
import io.github.stream29.codex.lite.cli.components.TextInputValue
import io.github.stream29.codex.lite.cli.settings.NewLineKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ComposerInputTest {
    @Test fun configuredNewLineAndSubmitKeysRemainComposerPolicy() = runTest {
        val state = TextInputState()
        var submissions by mutableStateOf(0)
        var observedDraft: TextInputValue? = null

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    ComposerInput(
                        state = state,
                        layout = TextInputLayout.create(
                            value = state.value,
                            width = 80,
                            firstLinePrefix = "> ",
                            continuationLinePrefix = "  ",
                        ),
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = { submissions++ },
                        onValueChanged = { value -> observedDraft = value },
                    )
                    Text("submissions:$submissions")
                }
            }

            sendKeyEvent(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierShift))
            awaitSnapshot()
            assertEquals("\n", state.value.text)
            assertEquals(state.value, observedDraft)
            assertEquals(0, submissions)

            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
            assertEquals(1, submissions)
        }
    }
}
