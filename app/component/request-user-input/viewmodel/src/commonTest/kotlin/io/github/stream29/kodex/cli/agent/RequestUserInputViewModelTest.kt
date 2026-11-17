package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
val requestUserInputViewModelTest by testSuite {
    test("declared selection is revision bound and invalid edits are inert") {
        runTest {
            val ports = RequestPorts()
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            val initial = model.pending()
            assertFalse(initial.canSubmit)
            assertFalse(model.selectOption(999, "scope", "Current module"))
            assertFalse(model.selectOption(initial.askId, "missing", "Current module"))
            assertFalse(model.selectOption(initial.askId, "scope", "Undeclared"))
            assertFalse(model.updateFreeForm(initial.askId, "scope", "not Other"))
            assertEquals(initial, model.state.value)
            assertTrue(model.selectOption(initial.askId, "scope", "Current module"))
            val selected = model.pending()
            assertEquals(RequestUserInputDraftAnswer.Option("Current module"), selected.answers["scope"])
            assertTrue(selected.canSubmit)
            model.submit(initial.askId, initial.revision)
            model.submit(999, 0)
            assertTrue(ports.submitted.isEmpty())
            assertEquals(selected, model.state.value)
            model.close()
        }
    }

    test("incomplete and Other drafts preserve validation and repeated-edit rules") {
        runTest {
            val ports = RequestPorts()
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.submit(1, 0)
            assertTrue(ports.submitted.isEmpty())
            assertTrue(model.selectOther(1, "scope"))
            assertFalse(model.pending().canSubmit)
            assertTrue(model.updateFreeForm(1, "scope", "   "))
            assertFalse(model.pending().canSubmit)
            assertTrue(model.updateFreeForm(1, "scope", " Whole workspace "))
            val valid = model.pending()
            assertTrue(valid.canSubmit)
            assertTrue(model.updateFreeForm(1, "scope", " Whole workspace "))
            assertEquals(valid.revision, model.pending().revision)
            assertTrue(model.selectOther(1, "scope"))
            assertEquals(valid.revision + 1, model.pending().revision)
            model.close()
        }
    }

    test("option-less questions accept direct text and completion keeps question order") {
        runTest {
            val request = requestEntry().copy(
                questions = RequestUserInputArgs(
                    listOf(
                        RequestUserInputQuestion("text", "Text", "Explain"),
                        requestEntry().questions.questions.single(),
                    ),
                ),
            )
            val ports = RequestPorts(request)
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            assertFalse(model.selectOther(request.askId, "text"))
            assertTrue(model.updateFreeForm(request.askId, "text", "  free form  "))
            model.selectOption(request.askId, "scope", "Current module")
            model.submit(request.askId, model.pending().revision)
            val (submittedAskId, response) = ports.submitted.single()
            assertEquals(request.askId, submittedAskId)
            assertEquals(listOf("text", "scope"), response.answers.keys.toList())
            assertEquals(listOf("user_note: free form"), response.answers.getValue("text").answers)
            assertEquals(listOf("Current module"), response.answers.getValue("scope").answers)
            assertEquals(listOf("submit"), ports.order)
            assertIs<RequestUserInputState.Idle>(model.state.value)
            model.close()
        }
    }

    test("same-askId projection ignores changed arguments and replacement discards drafts") {
        runTest {
            val ports = RequestPorts()
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOther(1, "scope")
            model.updateFreeForm(1, "scope", "Whole workspace")
            val edited = model.pending()
            ports.pending.value = requestEntry().copy(createdAt = Instant.fromEpochMilliseconds(2000))
            runCurrent()
            assertEquals(edited, model.state.value)
            ports.pending.value = requestEntry(askId = 2)
            runCurrent()
            assertEquals(2, model.pending().askId)
            assertTrue(model.pending().answers.isEmpty())
            assertFalse(model.updateFreeForm(1, "scope", "late"))
            model.close()
        }
    }

    test("Busy admission follows revision and submission freezes editing") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = RequestPorts().apply { submissionHook = { gate.await() } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOption(1, "scope", "Current module")
            val editing = model.pending()
            val submission = async { model.submit(editing.askId, editing.revision) }
            runCurrent()
            val frozen = model.pending()
            assertIs<RequestUserInputSubmissionState.Submitting>(frozen.submission)
            assertFalse(model.selectOther(frozen.askId, "scope"))
            model.submit(frozen.askId, editing.revision)
            model.submit(frozen.askId, frozen.revision)
            assertEquals(1, ports.submitted.size)
            gate.complete(Unit)
            submission.await()
            assertIs<RequestUserInputState.Idle>(model.state.value)
            model.close()
        }
    }

    test("failure retains drafts and edit clears failure before explicit retry") {
        runTest {
            val ports = RequestPorts().apply { submissionHook = { error("submission failed") } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOther(1, "scope")
            model.updateFreeForm(1, "scope", "answer")
            val editing = model.pending()
            model.submit(editing.askId, editing.revision)
            val failed = model.pending()
            assertEquals(editing.answers, failed.answers)
            assertEquals(editing.revision + 2, failed.revision)
            assertEquals("submission failed", assertIs<RequestUserInputSubmissionState.Failed>(failed.submission).message)
            assertTrue(failed.canSubmit)
            assertEquals(listOf("submit"), ports.order)
            model.updateFreeForm(failed.askId, "scope", "answer")
            assertIs<RequestUserInputSubmissionState.Editing>(model.pending().submission)
            ports.submissionHook = {}
            model.submit(failed.askId, model.pending().revision)
            assertIs<RequestUserInputState.Idle>(model.state.value)
            assertEquals(listOf("submit", "submit"), ports.order)
            model.close()
        }
    }

    test("dependency cancellation propagates and retains Submitting until replacement") {
        runTest {
            val ports = RequestPorts().apply { submissionHook = { throw CancellationException("cancel") } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOption(1, "scope", "Current module")
            assertFailsWith<CancellationException> { model.submit(1, model.pending().revision) }
            assertIs<RequestUserInputSubmissionState.Submitting>(model.pending().submission)
            assertEquals(listOf("submit"), ports.order)
            ports.pending.value = requestEntry(askId = 2)
            runCurrent()
            assertIs<RequestUserInputSubmissionState.Editing>(model.pending().submission)
            model.close()
        }
    }

    test("caller cancellation and close release observation but accepted submission drains") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = RequestPorts().apply { submissionHook = { gate.await() } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            assertEquals(1, ports.pending.subscriptionCount.value)
            model.selectOption(1, "scope", "Current module")
            val waiting = async { model.submit(1, model.pending().revision) }
            runCurrent()
            waiting.cancel()
            model.close()
            model.close()
            runCurrent()
            assertEquals(0, ports.pending.subscriptionCount.value)
            assertFalse(model.selectOther(1, "scope"))
            assertIs<RequestUserInputState.Idle>(model.state.value)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("submit"), ports.order)
            assertIs<RequestUserInputState.Idle>(model.state.value)
        }
    }

    listOf(true, false).forEach { succeeds ->
        test("replacement before late completion succeeds=$succeeds keeps new snapshot and remains submittable") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val ports = RequestPorts().apply {
                    submissionHook = {
                        gate.await()
                        if (!succeeds) error("late")
                    }
                }
                val model = createRequestUserInputViewModel(ports, backgroundScope)
                runCurrent()
                model.selectOption(1, "scope", "Current module")
                val waiting = async { model.submit(1, model.pending().revision) }
                runCurrent()
                ports.pending.value = requestEntry(askId = 2)
                runCurrent()
                val replacement = model.pending()
                gate.complete(Unit)
                waiting.await()
                assertEquals(replacement, model.state.value)
                assertEquals(listOf("submit"), ports.order)
                if (succeeds) {
                    ports.submissionHook = {}
                    model.selectOption(2, "scope", "Current module")
                    model.submit(2, model.pending().revision)
                    assertIs<RequestUserInputState.Idle>(model.state.value)
                }
                model.close()
            }
        }
    }

    test("owner cancellation closes observation and cancels accepted wait") {
        runTest {
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            val ports = RequestPorts().apply { submissionHook = { CompletableDeferred<Unit>().await() } }
            val model = createRequestUserInputViewModel(ports, owner)
            runCurrent()
            model.selectOption(1, "scope", "Current module")
            val waiting = async { model.submit(1, model.pending().revision) }
            runCurrent()
            ownerJob.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { waiting.await() }
            assertIs<RequestUserInputState.Idle>(model.state.value)
            assertEquals(0, ports.pending.subscriptionCount.value)
            assertEquals(listOf("submit"), ports.order)
        }
    }

    test("revision exhaustion remains explicit rather than wrapping") {
        val state = RequestUserInputState.Pending(
            askId = 1,
            arguments = requestEntry().questions,
            revision = Long.MAX_VALUE,
        )
        assertFailsWith<IllegalStateException> { state.nextRevision() }
    }

    test("empty dependency failure message retains existing representation exception") {
        runTest {
            val ports = RequestPorts().apply { submissionHook = { throw IllegalStateException("") } }
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            val model = createRequestUserInputViewModel(ports, owner)
            runCurrent()
            model.selectOption(1, "scope", "Current module")
            assertFailsWith<IllegalArgumentException> {
                model.submit(1, model.pending().revision)
            }
            assertIs<RequestUserInputSubmissionState.Submitting>(model.pending().submission)
            model.close()
            ownerJob.cancel()
        }
    }

    test("removing pending publishes Idle without hidden submission") {
        runTest {
            val ports = RequestPorts()
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            ports.pending.value = null
            runCurrent()
            assertIs<RequestUserInputState.Idle>(model.state.value)
            model.submit(1, 0)
            assertTrue(ports.order.isEmpty())
            model.close()
        }
    }
}

private class RequestPorts(initial: AskUserEntry = requestEntry()) :
    RequestUserInputDependencies {
    override val pending = MutableStateFlow<AskUserEntry?>(initial)
    val submitted = mutableListOf<Pair<Int, RequestUserInputResponse>>()
    val order = mutableListOf<String>()
    var submissionHook: suspend () -> Unit = {}

    override suspend fun submitAnswer(askId: Int, response: RequestUserInputResponse) {
        order += "submit"
        submitted += askId to response
        submissionHook()
    }
}

private fun RequestUserInputViewModel.pending(): RequestUserInputState.Pending =
    assertIs<RequestUserInputState.Pending>(state.value)

private fun requestEntry(askId: Int = 1): AskUserEntry =
    AskUserEntry(
        askId = askId,
        questions = RequestUserInputArgs(
            listOf(
                RequestUserInputQuestion(
                    id = "scope",
                    header = "Scope",
                    question = "Which scope should be changed?",
                    isOther = false,
                    options = listOf(
                        RequestUserInputQuestionOption("Current module", "Change only the active module."),
                        RequestUserInputQuestionOption("Whole workspace", "Change every affected module."),
                    ),
                ),
            ),
        ),
        createdAt = Instant.fromEpochMilliseconds(1000),
    )
