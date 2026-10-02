package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionResult
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
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

@OptIn(ExperimentalCoroutinesApi::class)
val requestUserInputViewModelTest by testSuite {
    test("declared selection is revision bound and invalid edits are inert") {
        runTest {
            val ports = RequestPorts()
            val model = requestUserInputViewModelFactory.create(ports, backgroundScope)
            runCurrent()
            val initial = model.pending()
            assertFalse(initial.canSubmit)
            assertFalse(model.selectOption("obsolete", "scope", "Current module"))
            assertFalse(model.selectOption(initial.callId, "missing", "Current module"))
            assertFalse(model.selectOption(initial.callId, "scope", "Undeclared"))
            assertFalse(model.updateFreeForm(initial.callId, "scope", "not Other"))
            assertEquals(initial, model.state.value)
            assertTrue(model.selectOption(initial.callId, "scope", "Current module"))
            val selected = model.pending()
            assertEquals(RequestUserInputDraftAnswer.Option("Current module"), selected.answers["scope"])
            assertTrue(selected.canSubmit)
            assertEquals(RequestUserInputSubmissionResult.StaleRevision,
                model.submit(initial.callId, initial.revision))
            assertEquals(RequestUserInputSubmissionResult.StaleCall, model.submit("obsolete", 0))
            model.close()
        }
    }

    test("incomplete and Other drafts preserve validation and repeated-edit rules") {
        runTest {
            val model = createRequestUserInputViewModel(RequestPorts(), backgroundScope)
            runCurrent()
            assertEquals(RequestUserInputSubmissionResult.Incomplete, model.submit("call_scope", 0))
            assertTrue(model.selectOther("call_scope", "scope"))
            assertFalse(model.pending().canSubmit)
            assertTrue(model.updateFreeForm("call_scope", "scope", "   "))
            assertFalse(model.pending().canSubmit)
            assertTrue(model.updateFreeForm("call_scope", "scope", " Whole workspace "))
            val valid = model.pending()
            assertTrue(valid.canSubmit)
            assertTrue(model.updateFreeForm("call_scope", "scope", " Whole workspace "))
            assertEquals(valid.revision, model.pending().revision)
            assertTrue(model.selectOther("call_scope", "scope"))
            assertEquals(valid.revision + 1, model.pending().revision)
            model.close()
        }
    }

    test("option-less questions accept direct text and completion keeps question order") {
        runTest {
            val request = requestEvent().copy(arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("text", "Text", "Explain"),
                requestEvent().arguments.questions.single(),
            )))
            val ports = RequestPorts(request)
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            assertFalse(model.selectOther(request.callId, "text"))
            assertTrue(model.updateFreeForm(request.callId, "text", "  free form  "))
            model.selectOption(request.callId, "scope", "Current module")
            assertEquals(RequestUserInputSubmissionResult.Submitted,
                model.submit(request.callId, model.pending().revision))
            val event = assertIs<StableRequestUserInputToolEvent>(ports.completed.single())
            val response = assertIs<StableRequestUserInputResult.Answered>(event.result).response
            assertEquals(listOf("text", "scope"), response.answers.keys.toList())
            assertEquals(listOf("user_note: free form"), response.answers.getValue("text").answers)
            assertEquals(listOf("Current module"), response.answers.getValue("scope").answers)
            assertEquals(request.callId, event.callId)
            assertEquals(request.itemId, event.itemId)
            assertEquals(request.arguments, event.arguments)
            assertEquals(listOf("complete", "resume"), ports.order)
            assertIs<RequestUserInputState.Idle>(model.state.value)
            model.close()
        }
    }

    test("same-call projection ignores changed arguments and replacement discards drafts") {
        runTest {
            val ports = RequestPorts()
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOther("call_scope", "scope")
            model.updateFreeForm("call_scope", "scope", "Whole workspace")
            val edited = model.pending()
            ports.pending.value = requestEvent().copy(itemId = ResponseItemId("updated-item"))
            runCurrent()
            assertEquals(edited, model.state.value)
            ports.pending.value = requestEvent("replacement")
            runCurrent()
            assertEquals("replacement", model.pending().callId)
            assertTrue(model.pending().answers.isEmpty())
            assertFalse(model.updateFreeForm("call_scope", "scope", "late"))
            model.close()
        }
    }

    test("Busy admission follows revision and submission freezes editing") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = RequestPorts().apply { completion = { gate.await(); 7 } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOption("call_scope", "scope", "Current module")
            val editing = model.pending()
            val submission = async { model.submit(editing.callId, editing.revision) }
            runCurrent()
            val frozen = model.pending()
            assertIs<RequestUserInputSubmissionState.Submitting>(frozen.submission)
            assertFalse(model.selectOther(frozen.callId, "scope"))
            assertEquals(RequestUserInputSubmissionResult.StaleRevision,
                model.submit(frozen.callId, editing.revision))
            assertEquals(RequestUserInputSubmissionResult.Busy,
                model.submit(frozen.callId, frozen.revision))
            gate.complete(Unit)
            assertEquals(RequestUserInputSubmissionResult.Submitted, submission.await())
            model.close()
        }
    }

    test("failure retains drafts and edit clears failure before explicit retry") {
        runTest {
            val ports = RequestPorts().apply { completion = { error("completion failed") } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOther("call_scope", "scope")
            model.updateFreeForm("call_scope", "scope", "answer")
            val editing = model.pending()
            assertEquals(RequestUserInputSubmissionResult.Failed("completion failed"),
                model.submit(editing.callId, editing.revision))
            val failed = model.pending()
            assertEquals(editing.answers, failed.answers)
            assertEquals(editing.revision + 2, failed.revision)
            assertIs<RequestUserInputSubmissionState.Failed>(failed.submission)
            assertTrue(failed.canSubmit)
            assertEquals(listOf("complete"), ports.order)
            model.updateFreeForm(failed.callId, "scope", "answer")
            assertIs<RequestUserInputSubmissionState.Editing>(model.pending().submission)
            ports.completion = { 8 }
            assertEquals(RequestUserInputSubmissionResult.Submitted,
                model.submit(failed.callId, model.pending().revision))
            assertEquals(listOf("complete", "complete", "resume"), ports.order)
            model.close()
        }
    }

    test("dependency cancellation propagates and retains Submitting until replacement") {
        runTest {
            val ports = RequestPorts().apply { completion = { throw CancellationException("cancel") } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOption("call_scope", "scope", "Current module")
            assertFailsWith<CancellationException> { model.submit("call_scope", model.pending().revision) }
            assertIs<RequestUserInputSubmissionState.Submitting>(model.pending().submission)
            assertEquals(listOf("complete"), ports.order)
            ports.pending.value = requestEvent("replacement")
            runCurrent()
            assertIs<RequestUserInputSubmissionState.Editing>(model.pending().submission)
            model.close()
        }
    }

    test("caller cancellation and close release observation but accepted completion drains") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = RequestPorts().apply { completion = { gate.await(); 9 } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            assertEquals(1, ports.pending.subscriptionCount.value)
            model.selectOption("call_scope", "scope", "Current module")
            val waiting = async { model.submit("call_scope", model.pending().revision) }
            runCurrent()
            waiting.cancel()
            model.close()
            model.close()
            runCurrent()
            assertEquals(0, ports.pending.subscriptionCount.value)
            assertFalse(model.selectOther("call_scope", "scope"))
            assertIs<RequestUserInputState.Idle>(model.state.value)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("complete", "resume"), ports.order)
            assertIs<RequestUserInputState.Idle>(model.state.value)
        }
    }

    listOf(true, false).forEach { succeeds ->
        test("replacement before late completion succeeds=$succeeds keeps new snapshot") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val ports = RequestPorts().apply {
                    completion = { gate.await(); if (!succeeds) error("late"); 3 }
                }
                val model = createRequestUserInputViewModel(ports, backgroundScope)
                runCurrent()
                model.selectOption("call_scope", "scope", "Current module")
                val waiting = async { model.submit("call_scope", model.pending().revision) }
                runCurrent()
                ports.pending.value = requestEvent("replacement")
                runCurrent()
                val replacement = model.pending()
                gate.complete(Unit)
                val result = waiting.await()
                if (succeeds) assertEquals(RequestUserInputSubmissionResult.Submitted, result)
                else assertEquals(RequestUserInputSubmissionResult.Failed("late"), result)
                assertEquals(replacement, model.state.value)
                assertEquals(if (succeeds) listOf("complete", "resume") else listOf("complete"), ports.order)
                // Existing success clears pendingEvent even after replacement; do not silently fix it.
                if (succeeds) {
                    model.selectOption("replacement", "scope", "Current module")
                    assertEquals(RequestUserInputSubmissionResult.StaleCall,
                        model.submit("replacement", model.pending().revision))
                }
                model.close()
            }
        }
    }

    test("owner cancellation closes observation and cancels accepted wait without resume") {
        runTest {
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            val ports = RequestPorts().apply { completion = { CompletableDeferred<Unit>().await(); 0 } }
            val model = createRequestUserInputViewModel(ports, owner)
            runCurrent()
            model.selectOption("call_scope", "scope", "Current module")
            val waiting = async { model.submit("call_scope", model.pending().revision) }
            runCurrent()
            ownerJob.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { waiting.await() }
            assertIs<RequestUserInputState.Idle>(model.state.value)
            assertEquals(0, ports.pending.subscriptionCount.value)
            assertEquals(listOf("complete"), ports.order)
        }
    }

    test("resume failure reports Failed after completed state already became Idle") {
        runTest {
            val ports = RequestPorts().apply { resume = { error("resume failed") } }
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            model.selectOption("call_scope", "scope", "Current module")
            assertEquals(RequestUserInputSubmissionResult.Failed("resume failed"),
                model.submit("call_scope", model.pending().revision))
            assertIs<RequestUserInputState.Idle>(model.state.value)
            assertEquals(listOf("complete", "resume"), ports.order)
            model.close()
        }
    }

    test("revision exhaustion remains explicit rather than wrapping") {
        val state = RequestUserInputState.Pending("call_scope", requestEvent().arguments,
            revision = Long.MAX_VALUE)
        assertFailsWith<IllegalStateException> { state.nextRevision() }
    }

    test("empty dependency failure message retains existing representation exception") {
        runTest {
            val ports = RequestPorts().apply { completion = { throw IllegalStateException("") } }
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            // A non-cancellation async failure follows the owner policy. Capture it rather than
            // failing runTest through a launched parent (the production owner policy is unchanged).
            val model = createRequestUserInputViewModel(ports, owner)
            runCurrent()
            model.selectOption("call_scope", "scope", "Current module")
            assertFailsWith<IllegalArgumentException> {
                model.submit("call_scope", model.pending().revision)
            }
            assertIs<RequestUserInputSubmissionState.Submitting>(model.pending().submission)
            model.close()
            ownerJob.cancel()
        }
    }

    test("removing pending publishes Idle without resume or hidden submission") {
        runTest {
            val ports = RequestPorts()
            val model = createRequestUserInputViewModel(ports, backgroundScope)
            runCurrent()
            ports.pending.value = null
            runCurrent()
            assertIs<RequestUserInputState.Idle>(model.state.value)
            assertEquals(RequestUserInputSubmissionResult.StaleCall, model.submit("call_scope", 0))
            assertTrue(ports.order.isEmpty())
            model.close()
        }
    }
}

private class RequestPorts(initial: PendingRequestUserInputToolEvent = requestEvent()) :
    RequestUserInputDependencies {
    override val pending = MutableStateFlow<PendingRequestUserInputToolEvent?>(initial)
    val completed = mutableListOf<StableCleanEvent.CompletedTool>()
    val order = mutableListOf<String>()
    var completion: suspend () -> Int = { 0 }
    var resume: () -> Unit = {}
    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
        order += "complete"
        this.completed += completed
        return completion()
    }
    override fun resumeRuntime() {
        order += "resume"
        resume()
    }
}

private fun RequestUserInputViewModel.pending(): RequestUserInputState.Pending =
    assertIs<RequestUserInputState.Pending>(state.value)

private fun requestEvent(callId: String = "call_scope"): PendingRequestUserInputToolEvent =
    PendingRequestUserInputToolEvent(
        callId = callId,
        arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion(
            id = "scope",
            header = "Scope",
            question = "Which scope should be changed?",
            isOther = false,
            options = listOf(
                RequestUserInputQuestionOption("Current module", "Change only the active module."),
                RequestUserInputQuestionOption("Whole workspace", "Change every affected module."),
            ),
        ))),
    )
