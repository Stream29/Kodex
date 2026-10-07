package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskDependencies
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
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
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
val suggestSubagentTaskFeedbackTest by testSuite {
    listOf(true, false).forEach { accepted ->
        listOf("", "   ", " Please revise the task ").forEach { feedback ->
            test("accepted=$accepted feedback='$feedback' preserves protocol and ordering") {
                runTest {
                    val ports = SuggestionPorts()
                    val model = suggestSubagentTaskViewModelFactory.create(ports, backgroundScope)
                    runCurrent()
                    model.updateFeedback("suggestion", feedback)
                    val pending = model.pending()
                    assertEquals(SuggestSubagentTaskSubmissionResult.Submitted,
                        model.submit(pending.callId, pending.revision, accepted))
                    val event = assertIs<StableSuggestSubagentTaskToolEvent>(ports.completed.single())
                    val response = assertIs<StableSuggestSubagentTaskResult.Completed>(event.result).response
                    if (accepted) {
                        val result = assertIs<SuggestSubagentTaskResponse.Accepted>(response)
                        assertNull(result.feedback)
                        assertEquals(ports.created, result.sessions)
                        assertEquals(listOf(pending.arguments to pending.configuration), ports.creations)
                    } else {
                        assertEquals(feedback.takeIf { it.isNotBlank() },
                            assertIs<SuggestSubagentTaskResponse.Rejected>(response).feedback)
                        assertTrue(ports.creations.isEmpty())
                    }
                    assertEquals(pending.arguments, event.arguments)
                    assertEquals(pending.callId, event.callId)
                    assertEquals(if (accepted) listOf("create", "complete", "resume")
                        else listOf("complete", "resume"), ports.order)
                    assertIs<SuggestSubagentTaskState.Idle>(model.state.value)
                    model.close()
                }
            }
        }
    }

    test("defaults sample once per call and catalog does not rewrite configuration") {
        runTest {
            val ports = SuggestionPorts()
            val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
            runCurrent()
            assertSame(ports.models, model.models)
            assertEquals(1, ports.defaultReads)
            val original = model.pending()
            ports.defaults = configuration().copy(model = OpenAiModelId("different"))
            ports.models.value = listOf(ModelInfo(OpenAiModelId("catalog"), "Catalog"))
            ports.pending.value = suggestionEvent().copy(arguments = SuggestSubagentTaskArgs(
                listOf(SuggestedSubagentTask("Updated", "Still same call"))))
            runCurrent()
            assertEquals(original, model.state.value)
            assertEquals(1, ports.defaultReads)
            ports.pending.value = suggestionEvent("replacement")
            runCurrent()
            assertEquals(ports.defaults, model.pending().configuration)
            assertEquals(2, ports.defaultReads)
            assertFalse(model.pending().rejecting)
            assertEquals("", model.pending().feedback)
            assertFalse(model.updateFeedback("suggestion", "obsolete"))
            assertFalse(model.updateWorkingDirectory("suggestion", Path("/obsolete")))
            model.close()
        }
    }

    test("component owns rejection mode and field commands merge latest complete draft") {
        runTest {
            val model = createSuggestSubagentTaskViewModel(SuggestionPorts(), backgroundScope)
            runCurrent()
            assertFalse(model.pending().rejecting)
            assertFalse(model.setRejecting("obsolete", true))
            assertTrue(model.setRejecting("suggestion", true))
            assertTrue(model.pending().rejecting)
            val rejecting = model.pending()
            assertTrue(model.setRejecting("suggestion", true))
            assertEquals(rejecting.revision, model.pending().revision)
            model.updateFeedback("suggestion", "raw feedback")
            model.updateModelConfiguration("suggestion", OpenAiModelId("missing-from-catalog"),
                ReasoningEffort.High, ServiceTier.Fast)
            model.updateRequestUserInputMode("suggestion", RequestUserInputMode.NoQuestion)
            model.updateWorkingDirectory("suggestion", Path("/selected"))
            val edited = model.pending()
            assertEquals(SuggestedSessionConfiguration(OpenAiModelId("missing-from-catalog"),
                ReasoningEffort.High, ServiceTier.Fast, Path("/selected"),
                RequestUserInputMode.NoQuestion), edited.configuration)
            assertEquals("raw feedback", edited.feedback)
            assertTrue(edited.rejecting)
            assertEquals(5, edited.revision.toInt())
            model.setRejecting("suggestion", false)
            assertFalse(model.pending().rejecting)
            assertEquals("raw feedback", model.pending().feedback)
            assertTrue(model.updateConfiguration("suggestion", configuration()))
            assertEquals(configuration(), model.pending().configuration)
            model.close()
            assertFalse(model.updateWorkingDirectory("suggestion", Path("/after-close")))
            assertFalse(model.updateFeedback("suggestion", "after-close"))
            assertFalse(model.updateConfiguration("suggestion", configuration()))
            assertFalse(model.updateModelConfiguration("suggestion", OpenAiModelId("late"),
                ReasoningEffort.Low, ServiceTier.Default))
            assertFalse(model.updateRequestUserInputMode("suggestion", RequestUserInputMode.AskUser))
            assertFalse(model.setRejecting("suggestion", true))
        }
    }

    test("Busy and stale revision are ordered before repeat effects and all edits freeze") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = SuggestionPorts().apply { creation = { gate.await(); created } }
            val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
            runCurrent()
            val editing = model.pending()
            val waiting = async { model.submit(editing.callId, editing.revision, true) }
            runCurrent()
            val frozen = model.pending()
            assertTrue(frozen.submitting)
            assertFalse(model.updateConfiguration("suggestion", configuration()))
            assertFalse(model.updateFeedback("suggestion", "late"))
            assertFalse(model.setRejecting("suggestion", true))
            assertFalse(model.updateWorkingDirectory("suggestion", Path("/late")))
            assertFalse(model.updateRequestUserInputMode("suggestion", RequestUserInputMode.NoQuestion))
            assertFalse(model.updateModelConfiguration("suggestion", OpenAiModelId("late"),
                ReasoningEffort.High, ServiceTier.Fast))
            assertEquals(SuggestSubagentTaskSubmissionResult.Stale,
                model.submit(frozen.callId, editing.revision, true))
            assertEquals(SuggestSubagentTaskSubmissionResult.Busy,
                model.submit(frozen.callId, frozen.revision, false))
            assertEquals(SuggestSubagentTaskSubmissionResult.Stale,
                model.submit("obsolete", frozen.revision, false))
            assertEquals(listOf("create"), ports.order)
            gate.complete(Unit)
            assertEquals(SuggestSubagentTaskSubmissionResult.Submitted, waiting.await())
            model.close()
        }
    }

    test("creation failure restores editable configuration and feedback without completion") {
        runTest {
            val ports = SuggestionPorts().apply { creation = { error("create failed") } }
            val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
            runCurrent()
            model.setRejecting("suggestion", true)
            model.updateFeedback("suggestion", "retained")
            model.updateWorkingDirectory("suggestion", Path("/edited"))
            val captured = model.pending()
            assertEquals(SuggestSubagentTaskSubmissionResult.Failed("create failed"),
                model.submit(captured.callId, captured.revision, true))
            val failed = model.pending()
            assertEquals(captured.configuration, failed.configuration)
            assertEquals(captured.feedback, failed.feedback)
            assertTrue(failed.rejecting)
            assertFalse(failed.submitting)
            assertEquals(captured.revision + 2, failed.revision)
            assertEquals(listOf("create"), ports.order)
            assertTrue(ports.completed.isEmpty())
            model.close()
        }
    }

    test("created Sessions survive completion failure and explicit retry creates again") {
        runTest {
            val ports = SuggestionPorts().apply { completion = { error("complete failed") } }
            val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
            runCurrent()
            assertEquals(SuggestSubagentTaskSubmissionResult.Failed("complete failed"),
                model.submit("suggestion", model.pending().revision, true))
            assertEquals(1, ports.creations.size)
            assertFalse(model.pending().submitting)
            assertEquals(listOf("create", "complete"), ports.order)
            // This is characterization, not an exactly-once fix or automatic retry.
            ports.completion = { 2 }
            assertEquals(SuggestSubagentTaskSubmissionResult.Submitted,
                model.submit("suggestion", model.pending().revision, true))
            assertEquals(2, ports.creations.size)
            assertEquals(listOf("create", "complete", "create", "complete", "resume"), ports.order)
            model.close()
        }
    }

    listOf("create", "complete").forEach { phase ->
        test("dependency cancellation during $phase retains Submitting without compensation") {
            runTest {
                val ports = SuggestionPorts().apply {
                    if (phase == "create") creation = { throw CancellationException("create") }
                    else completion = { throw CancellationException("complete") }
                }
                val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
                runCurrent()
                assertFailsWith<CancellationException> {
                    model.submit("suggestion", model.pending().revision, true)
                }
                assertTrue(model.pending().submitting)
                assertEquals(if (phase == "create") listOf("create") else listOf("create", "complete"),
                    ports.order)
                model.close()
            }
        }
    }

    test("cancelled renderer wait and close do not cancel accepted batch") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = SuggestionPorts().apply { creation = { gate.await(); created } }
            val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
            runCurrent()
            val waiting = async { model.submit("suggestion", model.pending().revision, true) }
            runCurrent()
            waiting.cancel()
            model.close()
            model.close()
            runCurrent()
            assertEquals(0, ports.pending.subscriptionCount.value)
            assertIs<SuggestSubagentTaskState.Idle>(model.state.value)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("create", "complete", "resume"), ports.order)
            assertIs<SuggestSubagentTaskState.Idle>(model.state.value)
            assertEquals(SuggestSubagentTaskSubmissionResult.Stale, model.submit("suggestion", 0, true))
        }
    }

    listOf(true, false).forEach { succeeds ->
        test("replacement during completion succeeds=$succeeds keeps the replacement draft") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val ports = SuggestionPorts().apply {
                    completion = { gate.await(); if (!succeeds) error("late"); 1 }
                }
                val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
                runCurrent()
                val waiting = async { model.submit("suggestion", model.pending().revision, true) }
                runCurrent()
                ports.pending.value = suggestionEvent("replacement")
                runCurrent()
                model.updateFeedback("replacement", "new draft")
                val replacement = model.pending()
                gate.complete(Unit)
                val result = waiting.await()
                if (succeeds) assertEquals(SuggestSubagentTaskSubmissionResult.Submitted, result)
                else assertEquals(SuggestSubagentTaskSubmissionResult.Failed("late"), result)
                assertEquals(replacement, model.state.value)
                assertEquals(1, ports.creations.size)
                assertEquals(if (succeeds) listOf("create", "complete", "resume")
                    else listOf("create", "complete"), ports.order)
                if (succeeds) {
                    // Existing late success clears the pendingEvent while retaining the new snapshot.
                    assertEquals(SuggestSubagentTaskSubmissionResult.Stale,
                        model.submit("replacement", replacement.revision, true))
                }
                model.close()
            }
        }
    }

    test("owner cancellation terminates pending observer and accepted operation") {
        runTest {
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            val ports = SuggestionPorts().apply {
                creation = { CompletableDeferred<Unit>().await(); created }
            }
            val model = createSuggestSubagentTaskViewModel(ports, owner)
            runCurrent()
            val waiting = async { model.submit("suggestion", model.pending().revision, true) }
            runCurrent()
            ownerJob.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { waiting.await() }
            assertEquals(0, ports.pending.subscriptionCount.value)
            assertIs<SuggestSubagentTaskState.Idle>(model.state.value)
            assertEquals(listOf("create"), ports.order)
        }
    }

    test("resume failure after completion stays Idle without recreating Sessions") {
        runTest {
            val ports = SuggestionPorts().apply { resume = { error("resume failed") } }
            val model = createSuggestSubagentTaskViewModel(ports, backgroundScope)
            runCurrent()
            assertEquals(SuggestSubagentTaskSubmissionResult.Failed("resume failed"),
                model.submit("suggestion", model.pending().revision, true))
            assertIs<SuggestSubagentTaskState.Idle>(model.state.value)
            assertEquals(1, ports.creations.size)
            assertEquals(listOf("create", "complete", "resume"), ports.order)
            model.close()
        }
    }
}

private class SuggestionPorts : SuggestSubagentTaskDependencies {
    override val pending = MutableStateFlow<PendingSuggestSubagentTaskToolEvent?>(suggestionEvent())
    override val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    var defaults = configuration()
    var defaultReads = 0
    val order = mutableListOf<String>()
    val creations = mutableListOf<Pair<SuggestSubagentTaskArgs, SuggestedSessionConfiguration>>()
    val completed = mutableListOf<StableCleanEvent.CompletedTool>()
    val created = listOf(SuggestedSessionMeta("memory://created", "Created"))
    var creation: suspend () -> List<SuggestedSessionMeta> = { created }
    var completion: suspend () -> Int = { 0 }
    var resume: () -> Unit = {}
    override fun defaultConfiguration(): SuggestedSessionConfiguration {
        defaultReads++
        return defaults
    }
    override suspend fun createSessions(
        arguments: SuggestSubagentTaskArgs,
        configuration: SuggestedSessionConfiguration,
    ): List<SuggestedSessionMeta> {
        order += "create"
        creations += arguments to configuration
        return creation()
    }
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

private fun SuggestSubagentTaskViewModel.pending(): SuggestSubagentTaskState.Pending =
    assertIs<SuggestSubagentTaskState.Pending>(state.value)

private fun configuration(): SuggestedSessionConfiguration = SuggestedSessionConfiguration(
    OpenAiModelId("test"), ReasoningEffort.Low, ServiceTier.Default,
    Path("."), RequestUserInputMode.AskUser,
)

private fun suggestionEvent(callId: String = "suggestion"): PendingSuggestSubagentTaskToolEvent =
    PendingSuggestSubagentTaskToolEvent(callId = callId,
        arguments = SuggestSubagentTaskArgs(listOf(SuggestedSubagentTask("Task", "Work"))))
