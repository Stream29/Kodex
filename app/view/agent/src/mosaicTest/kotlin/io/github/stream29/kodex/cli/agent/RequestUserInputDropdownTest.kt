package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestUserInputDropdownTest {
    @Test
    fun optionsUseOneDropdownAndOtherStillEnablesFreeFormInput() = runTest {
        val question = RequestUserInputQuestion(
            id = "scope",
            header = "Scope",
            question = "Which scope should be changed?",
            options = listOf(
                RequestUserInputQuestionOption(
                    label = "Alpha",
                    description = "First option",
                ),
                RequestUserInputQuestionOption(
                    label = "Beta",
                    description = "Second option",
                ),
            ),
        )
        var answer by mutableStateOf<RequestUserInputDraftAnswer?>(null)
        lateinit var dropdownState: TuiDropdownState

        runMosaicTest {
            val initial = setContentAndSnapshot {
                TuiPopupHost(modifier = Modifier.width(64).height(10)) {
                    dropdownState = rememberTuiDropdownState()
                    val state = RequestUserInputState.Pending(
                        callId = "call_scope",
                        arguments = RequestUserInputArgs(listOf(question)),
                        answers = answer?.let { mapOf(question.id to it) }.orEmpty(),
                    )
                    Column(modifier = Modifier.width(64).height(10)) {
                        RequestUserInputQuestionView(
                            callId = state.callId,
                            question = question,
                            state = state,
                            columns = 64,
                            autoFocus = true,
                            enabled = true,
                            dropdownState = dropdownState,
                            onFreeFormChanged = { _, _, text ->
                                answer = RequestUserInputDraftAnswer.FreeForm(text)
                                true
                            },
                        )
                    }
                    RequestUserInputQuestionDropdownMenu(
                        callId = state.callId,
                        question = question,
                        draft = answer,
                        dropdownState = dropdownState,
                        enabled = true,
                        onSelectOption = { _, _, label ->
                            answer = RequestUserInputDraftAnswer.Option(label)
                            true
                        },
                        onSelectOther = { _, _ ->
                            answer = RequestUserInputDraftAnswer.FreeForm("")
                            true
                        },
                    )
                }
            }

            assertTrue("[Choose an option]" in initial, initial)
            assertFalse("○" in initial, initial)
            assertFalse("●" in initial, initial)

            dropdownState.expand()
            val menu = awaitSnapshotContaining("Beta · Second option")
            assertTrue("Alpha · First option" in menu, menu)
            assertTrue("Other" in menu, menu)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val selected = awaitSnapshotContaining("[Alpha]")
            assertTrue("First option" in selected, selected)
            assertEquals(RequestUserInputDraftAnswer.Option("Alpha"), answer)

            dropdownState.expand()
            awaitSnapshotContaining("Other")
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val other = awaitSnapshotContaining("[Other]")

            assertTrue(other.lines().any { line -> line.trimStart() == ">" }, other)
            assertIs<RequestUserInputDraftAnswer.FreeForm>(answer)
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
