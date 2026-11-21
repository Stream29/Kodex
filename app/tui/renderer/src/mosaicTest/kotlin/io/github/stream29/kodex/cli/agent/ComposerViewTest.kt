package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerFailure
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.agent.contract.ComposerMode
import io.github.stream29.kodex.app.agent.contract.ComposerOperation
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val composerViewTest by testSuite {
    test("shared empty and running branches render the input and exact steer hint") {
        runMosaicTest {
            val empty = RenderComposerModel(ComposerState(ComposerOwnerId("agent-a")))
            val emptySnapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(empty, columns = 32, rows = 3, newLineKey = NewLineKey.ShiftEnter)
                }
            }
            assertTrue(">" in emptySnapshot, emptySnapshot)
            assertFalse("Submit to steer" in emptySnapshot, emptySnapshot)

            val running = RenderComposerModel(
                ComposerState(
                    ownerId = ComposerOwnerId("agent-a"),
                    text = "continue",
                    revision = 4,
                    running = true,
                ),
            )
            val runningSnapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(running, columns = 32, rows = 3, newLineKey = NewLineKey.ShiftEnter)
                }
            }
            assertTrue("Submit to steer" in runningSnapshot, runningSnapshot)
            assertEquals(ComposerMode.Running, running.state.value.mode)

            val ready = RenderComposerModel(
                ComposerState(
                    ownerId = ComposerOwnerId("agent-a"),
                    text = "ready",
                    revision = 3,
                ),
            )
            val readySnapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(ready, columns = 32, rows = 3, newLineKey = NewLineKey.ShiftEnter)
                }
            }
            assertTrue("ready" in readySnapshot, readySnapshot)
            assertFalse("Submit to steer" in readySnapshot, readySnapshot)
            assertEquals(ComposerMode.Ready, ready.state.value.mode)
        }
    }

    test("failed submission retains the draft without an inline failure banner") {
        val error = RenderComposerModel(
            ComposerState(
                ownerId = ComposerOwnerId("agent-a"),
                text = "retry explicitly",
                revision = 8,
                submission = ComposerSubmissionState.Failed(
                    ComposerFailure(ComposerOperation.Submit, "append failed"),
                ),
            ),
        )
        runMosaicTest {
            val errorSnapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(error, 48, 4, NewLineKey.ShiftEnter)
                }
            }
            assertFalse("Unable to submit:" in errorSnapshot, errorSnapshot)
            assertFalse("append failed" in errorSnapshot, errorSnapshot)
            assertTrue("retry explicitly" in errorSnapshot, errorSnapshot)
            assertEquals(ComposerMode.Error, error.state.value.mode)
        }
    }

    test("composer can be focused, edited, and submitted while a question is pending") {
        val pending = ComposerRequestInputPresentation.Pending(title = "Answer required", questionCount = 3)
        val model = RenderComposerModel(
            ComposerState(ownerId = ComposerOwnerId("agent-a"), text = "Follow-u", revision = 9, requestInput = pending),
        )
        runMosaicTest {
            val focusRequester = FocusRequester()
            val requestSnapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(model, 48, 4, NewLineKey.ShiftEnter, autoFocus = false, focusRequester = focusRequester)
                }
            }
            assertTrue("Input requested: Answer required (3)" in requestSnapshot, requestSnapshot)
            assertTrue(focusRequester.requestFocus())

            sendKeyEvent(KeyboardEvent('p'.code))
            awaitSnapshot()
            assertEquals(listOf("Follow-up"), model.updatedTexts)

            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
            repeat(4) { if (model.submittedRevisions.isEmpty()) awaitSnapshot() }
            assertEquals(listOf(10L), model.submittedRevisions)
            assertEquals("", model.state.value.text)
            assertEquals(pending, model.state.value.requestInput)
        }
    }

    test("steer preview lines ellipsize to terminal width and preserve exact message text") {
        val pending = listOf(
            StableUserMessage(
                listOf(ContentItem.InputText("a very long steer message that must fit")),
            ),
        )
        assertEquals(
            listOf(
                "Pending ste...",
                "  ↳ a very ...",
            ),
            pendingSteerPreviewLines(pending, columns = 14, maximumRows = 3),
        )
    }

    test("viewport sizing keeps at least one input row and never exceeds available rows") {
        assertEquals(2, boundedComposerRows(availableRows = 10, desiredRows = 2))
        assertEquals(3, boundedComposerRows(availableRows = 3, desiredRows = 20))
        assertEquals(1, boundedComposerRows(availableRows = 0, desiredRows = 20))

        runMosaicTest {
            val model = RenderComposerModel(
                ComposerState(
                    ownerId = ComposerOwnerId("agent-a"),
                    text = "one\ntwo\nthree\nfour",
                    revision = 1,
                ),
            )
            val snapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(model, columns = 10, rows = 2, newLineKey = NewLineKey.ShiftEnter)
                }
            }
            assertEquals(2, snapshot.lines().size)
        }
    }

    for (desiredRows in listOf(0, -1)) {
        test("shared sizing rejects nonpositive composer content rows=$desiredRows") {
            assertFailsWith<IllegalArgumentException> {
                boundedComposerRows(availableRows = 3, desiredRows = desiredRows)
            }
        }
    }

    for (maximumRows in listOf(0, -1)) {
        test("shared pending preview is empty with no available rows=$maximumRows") {
            val pending = listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))
            assertEquals(emptyList(), pendingSteerPreviewLines(pending, columns = 0, maximumRows = maximumRows))
        }
    }

    test("composer omits pending steer preview and inline failure while preserving editing steer hint") {
        val model = RenderComposerModel(
            ComposerState(
                ownerId = ComposerOwnerId("agent-a"),
                text = "retained steer",
                running = true,
                pendingSteer = listOf(StableUserMessage(listOf(ContentItem.InputText("queued")))),
                submission = ComposerSubmissionState.Editing,
            ),
        )
        runMosaicTest {
            val editingSteer = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(model, 48, 4, NewLineKey.ShiftEnter)
                }
            }
            assertTrue("Submit to steer" in editingSteer, editingSteer)
            assertTrue("retained steer" in editingSteer, editingSteer)
            assertFalse("Pending steer (" in editingSteer, editingSteer)
        }
    }

    test("newline and submit capture exact component revisions") {
        val model = RenderComposerModel(
            ComposerState(
                ownerId = ComposerOwnerId("agent-a"),
                text = "hello",
                revision = 12,
            ),
        )
        runMosaicTest {
            val focusRequester = FocusRequester()
            setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    ComposerView(
                        viewModel = model,
                        columns = 40,
                        rows = 4,
                        newLineKey = NewLineKey.ShiftEnter,
                        autoFocus = false,
                        focusRequester = focusRequester,
                    )
                }
            }
            assertTrue(focusRequester.requestFocus())

            sendKeyEvent(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierShift))
            awaitSnapshot()
            assertEquals(listOf("hello\n"), model.updatedTexts)
            assertTrue(model.state.value.text.endsWith("\n"))

            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
            repeat(4) { if (model.submittedRevisions.isEmpty()) awaitSnapshot() }
            assertEquals(listOf(13L), model.submittedRevisions)
            assertEquals("", model.state.value.text)
        }
    }

    for (failed in listOf(false, true)) {
        test("submitting preserves focus and blocks edits until completion failed=$failed") {
            val model = RenderComposerModel(
                ComposerState(
                    ownerId = ComposerOwnerId("agent-a"),
                    text = "draft",
                    cursorOffset = 5,
                    revision = 12,
                ),
            )
            var sessionsFocused = false
            runMosaicTest {
                val requester = FocusRequester()
                setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        Column {
                            TuiButton(
                                label = "Sessions",
                                modifier = Modifier.onFocusChanged { sessionsFocused = it.isFocused },
                                onClick = {},
                            )
                            ComposerView(
                                model, 40, 4, NewLineKey.ShiftEnter,
                                autoFocus = false, focusRequester = requester,
                            )
                        }
                    }
                }
                assertTrue(requester.requestFocus())
                model.state.value = model.state.value.copy(submission = ComposerSubmissionState.Submitting)
                while ("Submitting…" !in awaitSnapshot()) {}
                assertFalse(sessionsFocused, "Submitting must not move focus to Sessions")
                sendKeyEvent(KeyboardEvent('x'.code))
                sendKeyEvent(KeyboardEvent(127))
                sendKeyEvent(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierShift))
                sendKeyEvent(KeyboardEvent(13))
                sendPasteEvent(PasteEvent("pasted"))
                model.state.value = model.state.value.copy(
                    submission = if (failed) ComposerSubmissionState.Failed(
                        ComposerFailure(ComposerOperation.Submit, "failed"),
                    ) else ComposerSubmissionState.Editing,
                )
                while ("Submitting…" in awaitSnapshot()) {}
                assertEquals(emptyList(), model.updatedTexts)
                assertEquals(emptyList(), model.submittedRevisions)
                assertEquals("draft", model.state.value.text)
                sendKeyEvent(KeyboardEvent('x'.code))
                awaitSnapshot()
                assertFalse(sessionsFocused)
                assertEquals("draftx", model.state.value.text)
            }
        }
    }

    test("submission completion does not steal focus after explicit navigation") {
        val model = RenderComposerModel(
            ComposerState(
                ownerId = ComposerOwnerId("agent-a"),
                text = "draft",
                submission = ComposerSubmissionState.Submitting,
            ),
        )
        var sessionsClicked by mutableStateOf(0)
        runMosaicTest {
            setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    Column {
                        TuiButton(label = "Sessions $sessionsClicked", onClick = { sessionsClicked++ })
                        ComposerView(model, 40, 4, NewLineKey.ShiftEnter)
                    }
                }
            }
            sendKeyEvent(KeyboardEvent(9))
            model.state.value = model.state.value.copy(submission = ComposerSubmissionState.Editing)
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
            assertEquals(1, sessionsClicked)
            assertEquals(emptyList(), model.submittedRevisions)
        }
    }
}

private class RenderComposerModel(initial: ComposerState) : ComposerViewModel {
    private val mutableState = MutableStateFlow(initial)
    override val state = mutableState
    val updatedTexts = mutableListOf<String>()
    val submittedRevisions = mutableListOf<Long>()

    override fun update(text: String, cursorOffset: Int): Long {
        updatedTexts += text
        val current = mutableState.value
        val updated = current.copy(
            text = text,
            cursorOffset = cursorOffset,
            revision = current.revision + 1,
        )
        mutableState.value = updated
        return updated.revision
    }

    override fun clear(expectedRevision: Long): Boolean = false

    override suspend fun submit(expectedRevision: Long) {
        submittedRevisions += expectedRevision
        val current = mutableState.value
        mutableState.value = current.copy(
            text = "",
            cursorOffset = 0,
            revision = current.revision + 1,
            submission = ComposerSubmissionState.Editing,
        )
    }

    override fun close() {
        mutableState.value = mutableState.value.copy(
            lifecycle = ComposerLifecycle.Closed,
        )
    }
}
