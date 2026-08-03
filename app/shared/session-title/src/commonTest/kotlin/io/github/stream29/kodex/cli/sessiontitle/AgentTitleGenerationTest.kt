package io.github.stream29.kodex.cli.sessiontitle

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
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
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        val sessionIndex = repository.create()
        val root = repository.open(sessionIndex)
        root.runtime.modify { storage ->
            storage.initialize(
                KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    threadName = "Session $sessionIndex",
                ),
            )
        }
        val content = listOf(ContentItem.InputText("Plan the agent title handoff."))
        root.runtime.appendUserMessage(content)
        val capturedRequest = CompletableDeferred<Pair<OpenAiModelId, ReasoningEffort>>()
        val generation = AgentTitleGeneration(this)

        assertTrue(
            generation.start(
                agentState = root.runtime,
                content = content,
                enabled = true,
                model = null,
                reasoningEffort = ReasoningEffort.Low,
                generator = SessionTitleGenerator { _, model, reasoningEffort ->
                    capturedRequest.complete(model to reasoningEffort)
                    SessionTitleGenerationResult.Generated("Plan agent title handoff")
                },
            ),
        )

        assertEquals(DefaultSessionTitleModel to ReasoningEffort.Low, capturedRequest.await())
        val titleIndex = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) {
                root.runtime.latestIndex.first { index -> index >= 2 }
            }
        }
        assertEquals("Plan agent title handoff", root.storage.settings[titleIndex].threadName)
    }

    test("an explicit Agent rename wins over an in-flight generated title") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        val sessionIndex = repository.create()
        val root = repository.open(sessionIndex)
        root.runtime.modify { storage ->
            storage.initialize(
                KodexAgentSettings(
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
                reasoningEffort = ReasoningEffort.High,
                generator = SessionTitleGenerator { _, _, _ ->
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
