package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerCancellationPort
import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerFailure
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPort
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerResumePort
import io.github.stream29.kodex.app.agent.contract.ComposerRuntimePort
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerSteerPort
import io.github.stream29.kodex.app.agent.contract.ComposerSubmitPort
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionResult
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.RequestUserInputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
val composerViewModelTest by testSuite {
    test("draft edits are revisioned and failed edits clear the failure") {
        runTest {
            val ports = ComposerPorts()
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)

            assertEquals(0, model.state.value.revision)
            assertEquals(1, model.update("draft"))
            assertEquals(1, model.update("draft"))
            assertEquals(ComposerState(ComposerOwnerId("agent-a"), "draft", revision = 1),
                model.state.value.copy(running = false))

            ports.submitAction = { _, _ -> error("append failed") }
            assertEquals(ComposerSubmissionResult.Failed("append failed"), model.submit(1))
            val failed = model.state.value
            assertEquals("draft", failed.text)
            assertEquals(2, failed.revision)
            assertIs<ComposerSubmissionState.Failed>(failed.submission)
            assertEquals(
                "append failed",
                ports.reportedFailures.single().second.message,
            )

            assertEquals(3, model.update("draft"))
            assertIs<ComposerSubmissionState.Editing>(model.state.value.submission)
            model.close()
        }
    }

    test("revision admission rejects stale callbacks before any port command") {
        runTest {
            val ports = ComposerPorts()
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val revision = model.update("draft")

            assertEquals(ComposerSubmissionResult.Stale, model.submit(revision - 1))
            assertTrue(ports.order.isEmpty())
            model.update("new draft")
            assertEquals(ComposerSubmissionResult.Stale, model.submit(revision))
            assertEquals("new draft", model.state.value.text)
            model.close()
        }
    }

    test("idle submit persists before clear and then resumes the exact owner") {
        runTest {
            val ports = ComposerPorts()
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val revision = model.update("  hello  ")

            assertEquals(ComposerSubmissionResult.Submitted, model.submit(revision))
            assertEquals(listOf("submit:agent-a:hello", "resume:agent-a"), ports.order)
            assertEquals("", model.state.value.text)
            assertEquals(2, model.state.value.revision)
            model.close()
        }
    }

    test("running submit uses the steer port and distinguishes accepted from persisted") {
        runTest {
            val ports = ComposerPorts().apply { running.value = true }
            ports.steerAction = { _, content ->
                ports.pendingSteer.value =
                    ports.pendingSteer.value + StableUserMessage(content)
            }
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val revision = model.update("steer this")

            assertEquals(ComposerSubmissionResult.QueuedAsSteer, model.submit(revision))
            runCurrent()
            assertEquals(listOf("steer:agent-a:steer this"), ports.order)
            assertTrue(model.state.value.text.isEmpty())
            assertTrue(model.state.value.pendingSteer.single() is StableUserMessage)
            assertTrue(ports.resumeOwners.isEmpty())
            model.close()
        }
    }

    test("submit uses current runtime even before its renderer projection catches up") {
        runTest {
            val ports = ComposerPorts()
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val revision = model.update("steer now")
            ports.running.value = true
            assertFalse(model.state.value.running)
            assertEquals(ComposerSubmissionResult.QueuedAsSteer, model.submit(revision))
            assertEquals(listOf("steer:agent-a:steer now"), ports.order)
            assertTrue(ports.resumeOwners.isEmpty())
            model.close()
        }
    }

    test("caller cancellation propagates, keeps the draft, and does not call explicit cancel") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = ComposerPorts().apply {
                submitAction = { _, _ ->
                    gate.await()
                }
            }
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val revision = model.update("keep me")
            val waiting = async { model.submit(revision) }
            runCurrent()
            assertIs<ComposerSubmissionState.Submitting>(model.state.value.submission)

            waiting.cancel()
            assertFailsWith<CancellationException> { waiting.await() }
            assertEquals("keep me", model.state.value.text)
            assertIs<ComposerSubmissionState.Editing>(model.state.value.submission)
            model.cancel()
            assertEquals(listOf(ComposerOwnerId("agent-a")), ports.cancelOwners)
            model.close()
        }
    }

    test("close does not cancel accepted work and stale completion cannot reopen the child") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = ComposerPorts().apply {
                submitAction = { _, _ ->
                    gate.await()
                }
            }
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val revision = model.update("accepted")
            val waiting = async { model.submit(revision) }
            runCurrent()
            model.close()
            gate.complete(Unit)

            assertEquals(ComposerSubmissionResult.Submitted, waiting.await())
            assertEquals(listOf(ComposerOwnerId("agent-a")), ports.resumeOwners)
            assertEquals(io.github.stream29.kodex.app.agent.contract.ComposerMode.Closed,
                model.state.value.mode)
            assertEquals("accepted", model.state.value.text)
        }
    }

    test("replacement protects the new owner from a late old-owner callback") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val ports = ComposerPorts().apply {
                submitAction = { _, _ ->
                    gate.await()
                }
            }
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            val oldRevision = model.update("old")
            val waiting = async { model.submit(oldRevision) }
            runCurrent()

            model.close()
            val replacementPorts = ComposerPorts()
            val replacement = createComposerViewModel(
                ComposerOwnerId("agent-b"), replacementPorts, backgroundScope,
            )
            assertEquals(ComposerOwnerId("agent-b"), replacement.state.value.ownerId)
            val replacementRevision = replacement.update("new owner draft")
            gate.complete(Unit)

            assertEquals(ComposerSubmissionResult.Submitted, waiting.await())
            assertEquals(listOf(ComposerOwnerId("agent-a")), ports.resumeOwners)
            assertEquals(ComposerOwnerId("agent-b"), replacement.state.value.ownerId)
            assertEquals(replacementRevision, replacement.state.value.revision)
            assertEquals("new owner draft", replacement.state.value.text)
            assertEquals(emptyList(), replacementPorts.resumeOwners)
            replacement.close()
        }
    }

    test("request-input projection exposes presentation only and follows its sibling port") {
        runTest {
            val ports = ComposerPorts()
            val model = createComposerViewModel(ComposerOwnerId("agent-a"), ports, backgroundScope)
            ports.requestInputState.value = ComposerRequestInputPresentation.Pending(
                callId = "call-1",
                mode = RequestUserInputMode.AskUser,
                title = "Answer the question",
                questionCount = 2,
            )
            runCurrent()

            val state = model.state.value
            assertIs<ComposerRequestInputPresentation.Pending>(state.requestInput)
            assertEquals(io.github.stream29.kodex.app.agent.contract.ComposerMode.RequestInput,
                state.mode)
            assertEquals("call-1", (state.requestInput as ComposerRequestInputPresentation.Pending).callId)
            model.close()
        }
    }

    test("invalid owner identities and blank failure summaries remain explicit") {
        assertFailsWith<IllegalArgumentException> { ComposerOwnerId("") }
        assertFailsWith<IllegalArgumentException> {
            io.github.stream29.kodex.app.agent.contract.ComposerFailure(
                io.github.stream29.kodex.app.agent.contract.ComposerOperation.Submit,
                "",
            )
        }
    }
}

private fun createComposerViewModel(
    ownerId: ComposerOwnerId,
    ports: ComposerPorts,
    scope: kotlinx.coroutines.CoroutineScope,
): ComposerViewModel = io.github.stream29.kodex.cli.agent.createComposerViewModel(
    ownerId = ownerId,
    dependencies = ports,
    ownerScope = scope,
)

private class ComposerPorts : ComposerDependencies {
    val running = MutableStateFlow(false)
    val pendingSteer = MutableStateFlow<List<io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent.Steerable>>(
        emptyList(),
    )
    val requestInputState = MutableStateFlow<ComposerRequestInputPresentation>(
        ComposerRequestInputPresentation.None,
    )
    val order = mutableListOf<String>()
    val cancelOwners = mutableListOf<ComposerOwnerId>()
    val resumeOwners = mutableListOf<ComposerOwnerId>()
    val reportedFailures = mutableListOf<Pair<ComposerOwnerId, ComposerFailure>>()
    var submitAction: suspend (ComposerOwnerId, List<ContentItem>) -> Unit = { _, _ -> }
    var steerAction: suspend (ComposerOwnerId, List<ContentItem>) -> Unit = { _, _ -> }

    override val runtime: ComposerRuntimePort = object : ComposerRuntimePort {
        override val running = this@ComposerPorts.running
        override val pendingSteer = this@ComposerPorts.pendingSteer
    }
    override val submit: ComposerSubmitPort = ComposerSubmitPort { owner, content ->
        order += "submit:${owner.value}:${content.singleText()}"
        submitAction(owner, content)
    }
    override val steer: ComposerSteerPort = ComposerSteerPort { owner, content ->
        order += "steer:${owner.value}:${content.singleText()}"
        steerAction(owner, content)
    }
    override val cancellation: ComposerCancellationPort = ComposerCancellationPort { owner ->
        cancelOwners += owner
    }
    override val resume: ComposerResumePort = ComposerResumePort { owner ->
        order += "resume:${owner.value}"
        resumeOwners += owner
    }
    override val requestInput: ComposerRequestInputPort = object : ComposerRequestInputPort {
        override val presentation = this@ComposerPorts.requestInputState
    }
    override val failures: ComposerFailureReporter = ComposerFailureReporter { owner, failure ->
        reportedFailures += owner to failure
    }
}

private fun List<ContentItem>.singleText(): String =
    (single() as ContentItem.InputText).text
