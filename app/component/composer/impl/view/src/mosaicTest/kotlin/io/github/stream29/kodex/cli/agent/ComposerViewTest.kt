package io.github.stream29.kodex.cli.agent

import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerFailure
import io.github.stream29.kodex.app.agent.contract.ComposerMode
import io.github.stream29.kodex.app.agent.contract.ComposerOperation
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionResult
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.RequestUserInputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val composerViewTest by testSuite {
    test("empty and running branches render the input and exact steer hint") {
        runMosaicTest {
            val empty = RenderComposerModel(ComposerState(ComposerOwnerId("agent-a")))
            val emptySnapshot = setContentAndSnapshot {
                ComposerView(empty, columns = 32, rows = 3, newLineKey = NewLineKey.ShiftEnter)
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
                ComposerView(running, columns = 32, rows = 3, newLineKey = NewLineKey.ShiftEnter)
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
                ComposerView(ready, columns = 32, rows = 3, newLineKey = NewLineKey.ShiftEnter)
            }
            assertTrue("ready" in readySnapshot, readySnapshot)
            assertFalse("Submit to steer" in readySnapshot, readySnapshot)
            assertEquals(ComposerMode.Ready, ready.state.value.mode)
        }
    }

    test("error and request-input branches retain presentation without duplicating answers") {
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
        val request = RenderComposerModel(
            ComposerState(
                ownerId = ComposerOwnerId("agent-a"),
                text = "not sent",
                revision = 9,
                requestInput = ComposerRequestInputPresentation.Pending(
                    callId = "call-1",
                    mode = RequestUserInputMode.AskUser,
                    title = "Answer required",
                    questionCount = 3,
                ),
            ),
        )

        runMosaicTest {
            val errorSnapshot = setContentAndSnapshot {
                ComposerView(error, 48, 4, NewLineKey.ShiftEnter)
            }
            assertTrue("Unable to submit: append failed" in errorSnapshot, errorSnapshot)
            assertTrue("retry explicitly" in errorSnapshot, errorSnapshot)

            val requestSnapshot = setContentAndSnapshot {
                ComposerView(request, 48, 4, NewLineKey.ShiftEnter)
            }
            assertTrue("Input requested: Answer required (3)" in requestSnapshot, requestSnapshot)
            assertEquals(ComposerMode.RequestInput, request.state.value.mode)
            assertFalse("call-1" in requestSnapshot, requestSnapshot)
        }
    }

    test("steer preview wraps to terminal width and preserves the exact message text") {
        val pending = listOf(
            StableUserMessage(
                listOf(ContentItem.InputText("a very long steer message that must fit")),
            ),
        )
        val state = ComposerState(
            ownerId = ComposerOwnerId("agent-a"),
            text = "next",
            revision = 2,
            running = true,
            pendingSteer = pending,
        )
        assertEquals(
            listOf(
                "Pending ste...",
                "  ↳ a very ...",
            ),
            pendingSteerPreviewLines(pending, columns = 14, maximumRows = 3),
        )

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                ComposerView(RenderComposerModel(state), 14, 5, NewLineKey.ShiftEnter)
            }
            assertTrue("Pending ste..." in snapshot, snapshot)
            assertTrue("a very ..." in snapshot, snapshot)
            assertEquals(5, snapshot.lines().size)
        }
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
                ComposerView(model, columns = 10, rows = 2, newLineKey = NewLineKey.ShiftEnter)
            }
            assertEquals(2, snapshot.lines().size)
        }
    }

    test("host-owned preview and failure suppression never hides remaining Composer status") {
        val model = RenderComposerModel(ComposerState(
            ownerId = ComposerOwnerId("agent-a"),
            text = "retained steer",
            running = true,
            pendingSteer = listOf(StableUserMessage(listOf(ContentItem.InputText("queued")))),
            submission = ComposerSubmissionState.Failed(ComposerFailure(ComposerOperation.Steer, "steer failed")),
        ))
        runMosaicTest {
            val standaloneFailure = setContentAndSnapshot {
                ComposerView(model, 48, 4, NewLineKey.ShiftEnter, showPendingSteer = false)
            }
            assertTrue("Unable to submit: steer failed" in standaloneFailure, standaloneFailure)
            assertFalse("Pending steer (" in standaloneFailure, standaloneFailure)
            val hostOwnedFailure = setContentAndSnapshot {
                ComposerView(model, 48, 4, NewLineKey.ShiftEnter, showPendingSteer = false, showFailure = false)
            }
            assertTrue("Submit to steer" in hostOwnedFailure, hostOwnedFailure)
            assertTrue("retained steer" in hostOwnedFailure, hostOwnedFailure)
            assertFalse("Unable to submit:" in hostOwnedFailure, hostOwnedFailure)
            assertFalse("Pending steer (" in hostOwnedFailure, hostOwnedFailure)
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
                ComposerView(
                    viewModel = model,
                    columns = 40,
                    rows = 4,
                    newLineKey = NewLineKey.ShiftEnter,
                    autoFocus = false,
                    focusRequester = focusRequester,
                )
            }
            assertTrue(focusRequester.requestFocus())

            sendKeyEvent(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierShift))
            awaitSnapshot()
            assertEquals(listOf("hello\n"), model.updatedTexts)
            assertTrue(model.state.value.text.endsWith("\n"))

            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
            assertEquals(listOf(13L), model.submittedRevisions)
            assertEquals("", model.state.value.text)
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

    override suspend fun submit(expectedRevision: Long): ComposerSubmissionResult {
        submittedRevisions += expectedRevision
        val current = mutableState.value
        mutableState.value = current.copy(
            text = "",
            cursorOffset = 0,
            revision = current.revision + 1,
            submission = ComposerSubmissionState.Editing,
        )
        return ComposerSubmissionResult.Submitted
    }

    override fun cancel() = Unit

    override fun close() {
        mutableState.value = mutableState.value.copy(
            lifecycle = io.github.stream29.kodex.app.agent.contract.ComposerLifecycle.Closed,
        )
    }
}
