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
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionResult
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestUserInputOptionsTest {
    @Test
    fun panelStartsWithTheQuestionWithoutARedundantTitle() = runTest {
        val state = RequestUserInputState.Pending(
            callId = "call_scope",
            arguments = RequestUserInputArgs(
                questions = listOf(
                    RequestUserInputQuestion(
                        id = "scope",
                        header = "Scope",
                        question = "Which scope should be changed?",
                        options = listOf(
                            RequestUserInputQuestionOption(
                                label = "Alpha",
                                description = "First option",
                            ),
                        ),
                    ),
                ),
            ),
        )

        runMosaicTest {
            val rendered = setContentAndSnapshot {
                RequestUserInputPanel(
                    viewModel = FakeRequestUserInputViewModel(state),
                    state = state,
                    columns = 64,
                    rows = 6,
                )
            }

            assertEquals(
                "Scope: Which scope should be changed?",
                rendered.lineSequence().first(String::isNotBlank).trimEnd(),
            )
            assertFalse("Input requested" in rendered)
        }
    }

    @Test
    fun optionsRemainButtonsAndOtherStillEnablesFreeFormInput() = runTest {
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

        runMosaicTest {
            val initial = setContentAndSnapshot {
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
                        onSelectOption = { _, _, label ->
                            answer = RequestUserInputDraftAnswer.Option(label)
                            true
                        },
                        onSelectOther = { _, _ ->
                            answer = RequestUserInputDraftAnswer.FreeForm("")
                            true
                        },
                        onFreeFormChanged = { _, _, text ->
                            answer = RequestUserInputDraftAnswer.FreeForm(text)
                            true
                        },
                    )
                }
            }

            assertTrue("[○ Alpha]" in initial, initial)
            assertTrue("[○ Beta]" in initial, initial)
            assertTrue("[○ Other]" in initial, initial)
            assertTrue("First option" in initial, initial)
            assertFalse("Choose an option" in initial, initial)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val selected = awaitSnapshotContaining("[● Alpha]")
            assertEquals(RequestUserInputDraftAnswer.Option("Alpha"), answer)
            assertTrue("First option" in selected, selected)

            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val other = awaitSnapshotContaining("[● Other]")

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

private class FakeRequestUserInputViewModel(
    state: RequestUserInputState,
) : RequestUserInputViewModel {
    override val state: StateFlow<RequestUserInputState> = MutableStateFlow(state)

    override fun selectOption(
        callId: String,
        questionId: String,
        label: String,
    ): Boolean = false

    override fun selectOther(
        callId: String,
        questionId: String,
    ): Boolean = false

    override fun updateFreeForm(
        callId: String,
        questionId: String,
        text: String,
    ): Boolean = false

    override suspend fun submit(
        callId: String,
        expectedRevision: Long,
    ): RequestUserInputSubmissionResult = RequestUserInputSubmissionResult.Incomplete

    override fun close() = Unit
}
