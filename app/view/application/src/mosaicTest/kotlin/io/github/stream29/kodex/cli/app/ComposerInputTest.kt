package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test fun runningComposerWithDraftRendersSubmitToSteerHint() = runTest {
        val text = "Adjust the active turn"
        val state = TextInputState(TextInputValue(text = text, cursorOffset = text.length))

        assertNull(submitToSteerHint(running = false, draft = text))
        assertNull(submitToSteerHint(running = true, draft = " \n"))
        assertEquals("Submit to steer", submitToSteerHint(running = true, draft = text))

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                ComposerInput(
                    state = state,
                    layout = TextInputLayout.create(
                        value = state.value,
                        width = 80,
                        firstLinePrefix = "> ",
                        continuationLinePrefix = "  ",
                    ),
                    newLineKey = NewLineKey.ShiftEnter,
                    submitHint = submitToSteerHint(running = true, draft = state.value.text),
                    onSubmit = {},
                )
            }

            assertTrue("Submit to steer" in snapshot, snapshot)
        }
    }

    @Test fun composerNewlineIsAnAtomicUndoAndRedoTransaction() = runTest {
        val state = TextInputState()

        runMosaicTest {
            setContentAndSnapshot {
                ComposerInput(
                    state = state,
                    layout = TextInputLayout.create(
                        value = state.value,
                        width = 10,
                        softWrap = true,
                    ),
                    newLineKey = NewLineKey.ShiftEnter,
                    onSubmit = {},
                )
            }

            sendKeyEvent(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierShift))
            awaitSnapshot()
            assertEquals(TextInputValue("\n"), state.value)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'z'.code,
                    modifiers = KeyboardEvent.ModifierCtrl,
                ),
            )
            awaitSnapshot()
            assertEquals(TextInputValue(), state.value)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'y'.code,
                    modifiers = KeyboardEvent.ModifierCtrl,
                ),
            )
            awaitSnapshot()
            assertEquals(TextInputValue("\n"), state.value)
        }
    }

    @Test fun composerViewportUsesOnlyItsAvailableRows() {
        assertEquals(2, boundedComposerRows(availableRows = 10, desiredRows = 2))
        assertEquals(3, boundedComposerRows(availableRows = 3, desiredRows = 20))
        assertEquals(1, boundedComposerRows(availableRows = 0, desiredRows = 20))
    }

    @Test fun pendingSteerPreviewShowsUserAndAgentMessages() {
        val pending = listOf(
            StableUserMessage(
                listOf(ContentItem.InputText("Adjust the active turn")),
            ),
            StableAgentMessage(
                author = "/worker",
                recipient = "/root",
                content = listOf(AgentMessageInputContent.InputText("FINAL_ANSWER: Done")),
            ),
        )

        assertEquals(
            listOf(
                "Pending steer (2)",
                "  ↳ Adjust the active turn",
                "  ↳ /worker → /root: FINAL_ANSWER: Done",
            ),
            pendingSteerPreviewLines(
                pending = pending,
                columns = 80,
                maximumRows = 6,
            ),
        )
    }
}
