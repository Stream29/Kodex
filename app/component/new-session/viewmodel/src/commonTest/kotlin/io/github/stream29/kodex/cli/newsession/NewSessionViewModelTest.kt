package io.github.stream29.kodex.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.cli.rpc.RpcNewSessionSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val newSessionViewModelTest by testSuite {
    test("real frontend fixture closeAndJoin closes an unclosed singleton child before returning") {
        withContext(Dispatchers.Default) {
            val fixture = startRpcFrontendFixture(this)
            val model = fixture.draft()
            model.composer.update("local only")
            val closing = async { fixture.closeAndJoin() }
            try {
                withTimeout(10.seconds) { closing.await() }
                assertEquals(ComposerLifecycle.Closed, model.composer.state.value.lifecycle)
                assertTrue(model.runtimeConfiguration.state.value.closed)
                assertFailsWith<IllegalStateException> { model.composer.update("late") }
                assertFailsWith<IllegalStateException> { model.updateModel(OpenAiModelId("late")) }
            } finally {
                model.close()
                withContext(NonCancellable) { closing.await() }
            }
        }
    }
    test("factory parent cancellation closes original children without closing borrowed global or registry") {
        withRpcFrontend {
            val parent = Job(coroutineContext[Job])
            val factory = DefaultNewSessionViewModelFactory(views, sessions, global,
                CoroutineScope(coroutineContext + parent))
            val model = factory.create(NewSessionViewModelArguments(root))
            model.composer.update("retained")
            parent.cancelAndJoin()
            assertEquals(ComposerLifecycle.Closed, model.composer.state.value.lifecycle)
            assertTrue(model.runtimeConfiguration.state.value.closed)
            assertTrue(parent.children.none())
            assertFailsWith<IllegalStateException> { model.composer.update("late") }
            assertFailsWith<IllegalStateException> { model.updateModel(OpenAiModelId("late")) }
            global.ensureActive()
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
            val sibling = draft()
            try {
                sibling.composer.update("still usable")
                val persisted = assertNotNull(sibling.submit(sibling.composer.state.value.revision))
                assertSame(persisted, sessions.open(persisted.sessionIndex))
            } finally { sibling.close() }
        }
    }
    test("Agent Composer submit cannot report persistence or consume New Session input") {
        withRpcFrontend {
            val draft = draft()
            try {
                draft.composer.update("keep draft", 10)
                val result = draft.composer.submit(draft.composer.state.value.revision)
                assertIs<io.github.stream29.kodex.app.agent.contract.ComposerSubmissionResult.Failed>(result)
                assertEquals("keep draft", draft.composer.state.value.text)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
            } finally { draft.close() }
        }
    }
    test("stable runtime child and defaults page edit the same backend fields while cwd stays local") {
        withRpcFrontend {
            val draft = draft()
            val page = RpcNewSessionSettings(global, this)
            val persisted = create()
            val original = persisted.settings.value
            try {
                val cwd = SystemTemporaryDirectory
                draft.updateWorkingDirectory(cwd)
                draft.composer.update("retained")
                val child = draft.runtimeConfiguration
                assertSame(child, draft.runtimeConfiguration)
                assertSame(global.models, draft.models)
                child.updateModelConfiguration(OpenAiModelId("selected"), ReasoningEffort.Max, ServiceTier.Fast)
                child.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                val changed = page.state.first {
                    it.settings.model == OpenAiModelId("selected") &&
                        it.settings.requestUserInputMode == RequestUserInputMode.NoQuestion
                }
                assertEquals(ReasoningEffort.Max, changed.settings.reasoningEffort)
                assertEquals(ServiceTier.Fast, changed.settings.serviceTier)
                draft.settings.first {
                    it.model == OpenAiModelId("selected") &&
                        it.requestUserInputMode == RequestUserInputMode.NoQuestion && it.cwd == cwd
                }
                assertEquals("retained", draft.composer.state.value.text)
                page.updateModel(changed.revision, OpenAiModelId("from-settings"))
                val afterModel = page.state.first { it.settings.model == OpenAiModelId("from-settings") }
                page.updateReasoningEffort(afterModel.revision, ReasoningEffort.High)
                val afterEffort = page.state.first { it.settings.reasoningEffort == ReasoningEffort.High }
                page.updateServiceTier(afterEffort.revision, ServiceTier.Ultrafast)
                val afterTier = page.state.first { it.settings.serviceTier == ServiceTier.Ultrafast }
                page.updateRequestUserInputMode(afterTier.revision, RequestUserInputMode.AskUser)
                page.state.first { it.settings.requestUserInputMode == RequestUserInputMode.AskUser }
                draft.settings.first {
                    it.model == OpenAiModelId("from-settings") && it.reasoning.effort == ReasoningEffort.High &&
                        it.serviceTier == ServiceTier.Ultrafast && it.requestUserInputMode == RequestUserInputMode.AskUser
                }
                assertEquals(cwd, draft.settings.value.cwd)
                assertEquals(original, persisted.settings.value)
                assertEquals(1, services.global.getSessionCatalog(true).size)
                draft.close()
                assertTrue(child.state.value.closed)
                child.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                assertEquals(RequestUserInputMode.AskUser, services.global.getSettings().newSession.requestUserInputMode)
                global.ensureActive()
            } finally { draft.close(); page.close() }
        }
    }
    test("success returns a registered persisted handle resets cwd and permits a second creation") {
        withRpcFrontend {
            val draft = draft()
            try {
                draft.updateWorkingDirectory(SystemTemporaryDirectory)
                draft.updateModel(OpenAiModelId("shared-model"))
                global.settings.first { it.newSession.model == OpenAiModelId("shared-model") }
                val revision = draft.composer.update("first message")
                val first = assertNotNull(draft.submit(revision))
                assertSame(first, sessions.open(first.sessionIndex))
                assertEquals("Session ${first.sessionIndex}", first.settings.value.threadName)
                assertEquals(OpenAiModelId("shared-model"), first.settings.value.model)
                assertEquals(SystemTemporaryDirectory, first.settings.value.cwd)
                assertEquals("", draft.composer.state.value.text)
                draft.settings.first { it.cwd == root }
                assertEquals(ComposerLifecycle.Open, draft.composer.state.value.lifecycle)
                assertNull(draft.submit(revision))
                val next = draft.composer.update("second message")
                assertNull(draft.submit(revision))
                assertEquals("second message", draft.composer.state.value.text)
                val second = assertNotNull(draft.submit(next))
                assertNotEquals(first.sessionIndex, second.sessionIndex)
                assertEquals(root, second.settings.value.cwd)
                assertEquals(2, services.global.getSessionCatalog(true).size)
            } finally { draft.close() }
        }
    }
}
