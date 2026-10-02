package io.github.stream29.kodex.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

val newSessionViewModelTest by testSuite {
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
