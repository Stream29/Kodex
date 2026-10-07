package io.github.stream29.kodex.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val newSessionViewModelTest by testSuite {
    test("real frontend fixture closeAndJoin closes an unclosed factory draft before returning") {
        withContext(Dispatchers.Default) {
            val fixture = startRpcFrontendFixture(this)
            val model = fixture.draft("unclosed child")
            model.composer.update("local only")
            val closing = async { fixture.closeAndJoin() }
            try {
                withTimeout(10.seconds) { closing.await() }
                assertEquals(ComposerLifecycle.Closed, model.composer.state.value.lifecycle)
                assertFailsWith<IllegalStateException> { model.composer.update("late") }
                assertFailsWith<IllegalStateException> { model.updateModel(OpenAiModelId("late")) }
            } finally {
                model.close()
                withContext(NonCancellable) { closing.await() }
            }
        }
    }
    test("factory parent cancellation without explicit close closes original Composer and all observers") {
        withRpcFrontend {
            val parent = Job(coroutineContext[Job])
            val factory = DefaultNewSessionViewModelFactory(views, sessions, models,
                CoroutineScope(coroutineContext + parent))
            val model = factory.create(NewSessionViewModelArguments("Owned", testSettings("", root)))
            model.composer.update("retained")
            parent.cancelAndJoin() // Not model.close(); exercise the supplied factory owner.
            assertEquals(ComposerLifecycle.Closed, model.composer.state.value.lifecycle)
            assertTrue(parent.children.none())
            assertFailsWith<IllegalStateException> { model.composer.update("late") }
            assertFailsWith<IllegalStateException> { model.updateModel(OpenAiModelId("late")) }
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
            val sibling = draft("borrowed backend still alive")
            try {
                sibling.composer.update("local")
                assertEquals(ComposerLifecycle.Open, sibling.composer.state.value.lifecycle)
            } finally { sibling.close() }
        }
    }
    test("draft Agent submit port cannot report persistence or consume the input") {
        withRpcFrontend {
            val draft = draft()
            try {
                draft.composer.update("keep draft", 10)
                val revision = draft.composer.state.value.revision
                val result = draft.composer.submit(revision)
                assertIs<io.github.stream29.kodex.app.agent.contract.ComposerSubmissionResult.Failed>(result)
                assertEquals("keep draft", draft.composer.state.value.text)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
            } finally {
                draft.close()
            }
        }
    }
    test("stable runtime child edits only its exact draft tuple and closes without materializing") {
        withRpcFrontend {
            val draft = draft("target")
            val other = draft("other")
            try {
                draft.rename("keep title")
                val cwd = draft.settings.value.cwd
                val child = draft.runtimeConfiguration
                assertSame(child, draft.runtimeConfiguration)
                child.updateModelConfiguration(OpenAiModelId("selected"), ReasoningEffort.Max, ServiceTier.Fast)
                child.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                assertEquals(OpenAiModelId("selected"), draft.settings.value.model)
                assertEquals(ReasoningEffort.Max, draft.settings.value.reasoning.effort)
                assertEquals(ServiceTier.Fast, draft.settings.value.serviceTier)
                assertEquals(RequestUserInputMode.NoQuestion, draft.settings.value.requestUserInputMode)
                assertEquals("keep title", draft.settings.value.threadName)
                assertEquals(cwd, draft.settings.value.cwd)
                assertNotEquals(OpenAiModelId("selected"), other.settings.value.model)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                draft.close()
                assertTrue(child.state.value.closed)
                child.updateRequestUserInputMode(RequestUserInputMode.AskUser)
                assertEquals(RequestUserInputMode.NoQuestion, draft.settings.value.requestUserInputMode)
            } finally { draft.close(); other.close() }
        }
    }
    test("draft settings stay local until one captured materialization") {
        withRpcFrontend {
            val draft = draft()
            try {
                draft.updateModel(OpenAiModelId("local-model"))
                draft.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                draft.rename("explicit title")
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                draft.composer.update("first message", 13)
                val persisted = draft.materialize()
                assertEquals("explicit title", persisted.settings.value.threadName)
                assertEquals(OpenAiModelId("local-model"), persisted.settings.value.model)
                assertEquals(RequestUserInputMode.NoQuestion, persisted.settings.value.requestUserInputMode)
                assertTrue(draft.composer.state.value.text.isEmpty())
                assertEquals(1, services.global.getSessionCatalog(true).size)
                assertFailsWith<IllegalStateException> { draft.updateModel(OpenAiModelId("late")) }
            } finally { draft.close() }
        }
    }
    test("numbered display labels are not sent as explicit backend titles") {
        withRpcFrontend {
            val draft = draft("New Session 2")
            try {
                assertEquals("New Session 2", draft.name.value)
                val persisted = draft.materialize()
                assertEquals("Session ${persisted.sessionIndex}", persisted.settings.value.threadName)
            } finally { draft.close() }
        }
    }
    test("clearing an explicit title restores the local label") {
        withRpcFrontend {
            val draft = draft("New Session 3")
            try {
                draft.rename("local")
                draft.name.first { it == "local" }
                draft.clearExplicitThreadName()
                draft.name.first { it == "New Session 3" }
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
            } finally { draft.close() }
        }
    }
}
