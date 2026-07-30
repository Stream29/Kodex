package io.github.stream29.codex.lite.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertNull

val sessionRepositoryViewModelTest by testSuite {
    test("publishes catalog titles without materializing root view models") {
        coroutineScope {
            val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
            val sessionIndex = repository.create()
            repository.open(sessionIndex).runtime.modify { storage ->
                storage.initialize(
                    CodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Review session title catalog",
                    ),
                )
            }
            val viewModel = SessionRepositoryViewModel(repository)
            try {
                viewModel.refresh()

                val entry = viewModel.state.value.sessions.single()
                assertEquals("Review session title catalog", entry.threadName)
                assertNull(entry.viewModel)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}
