package io.github.stream29.codex.lite.cli.sessiontitle

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val agentTitleGenerationTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("persists one generated title through the AgentState") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val sessionIndex = repository.create()
        val root = repository.open(sessionIndex)
        root.runtime.modify { storage ->
            storage.initialize(
                CodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    threadName = "Session $sessionIndex",
                ),
            )
        }
        val content = listOf(ContentItem.InputText("Plan the agent title handoff."))
        root.runtime.appendUserMessage(content)
        val capturedModel = CompletableDeferred<OpenAiModelId>()
        val generation = AgentTitleGeneration(this)

        assertTrue(
            generation.start(
                agentState = root.runtime,
                content = content,
                enabled = true,
                model = null,
                generator = SessionTitleGenerator { _, model ->
                    capturedModel.complete(model)
                    SessionTitleGenerationResult.Generated("Plan agent title handoff")
                },
            ),
        )

        assertEquals(DefaultSessionTitleModel, capturedModel.await())
        val titleIndex = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) {
                root.runtime.latestIndex.first { index -> index >= 2 }
            }
        }
        assertEquals("Plan agent title handoff", root.storage.settings[titleIndex].threadName)
    }

    test("an explicit Agent rename wins over an in-flight generated title") {
        val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
        val sessionIndex = repository.create()
        val root = repository.open(sessionIndex)
        root.runtime.modify { storage ->
            storage.initialize(
                CodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    threadName = "Session $sessionIndex",
                ),
            )
        }
        val content = listOf(ContentItem.InputText("Keep this explicit title."))
        root.runtime.appendUserMessage(content)
        val generationStarted = CompletableDeferred<Unit>()
        val generation = AgentTitleGeneration(this)

        assertTrue(
            generation.start(
                agentState = root.runtime,
                content = content,
                enabled = true,
                model = OpenAiModelId("title-model"),
                generator = SessionTitleGenerator { _, _ ->
                    generationStarted.complete(Unit)
                    CompletableDeferred<SessionTitleGenerationResult>().await()
                },
            ),
        )
        generationStarted.await()

        val renamedAt = generation.renameThread(root.runtime, "Manual title")

        assertEquals("Manual title", root.storage.settings[renamedAt].threadName)
    }
}
}
