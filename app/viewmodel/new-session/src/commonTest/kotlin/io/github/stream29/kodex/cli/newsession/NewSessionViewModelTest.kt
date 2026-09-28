package io.github.stream29.kodex.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

val newSessionViewModelTest by testSuite {
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
