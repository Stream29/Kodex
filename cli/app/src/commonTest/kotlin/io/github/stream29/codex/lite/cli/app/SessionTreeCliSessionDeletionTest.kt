package io.github.stream29.codex.lite.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.cli.auth.InMemoryCodexAuthStore
import io.github.stream29.codex.lite.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings
import io.github.stream29.codex.lite.cli.settings.InMemoryCodexGlobalSettings
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

val sessionTreeCliSessionDeletionTest by testSuite {
    test("deleting an open session removes its persistent root and tab") {
        coroutineScope {
            val dependencies = testCodexAgentDependencies()
            val repository = InMemoryCodexSessionRepository(dependencies)
            val sessions = createSessionRepositoryViewModel(repository)
            val viewModel = SessionTreeCliViewModel(
                repository = sessions,
                globalSettings = InMemoryCodexGlobalSettings(
                    CodexGlobalSettings(
                        codexHome = Path("codex-home"),
                        newSession = CodexNewSessionSettings(model = OpenAiModelId("test-model")),
                    ),
                ),
                workingDirectory = Path("."),
                modelCatalog = dependencies.modelCatalog,
                authStore = InMemoryCodexAuthStore(
                    OpenAiSubscriptionAuthState(accessToken = "test-access-token"),
                ),
                parentScope = this,
            )
            val sessionIndex = repository.create()
            repository.open(sessionIndex).runtime.modify { storage ->
                storage.initialize(
                    CodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Delete me",
                    ),
                )
            }

            try {
                viewModel.initialize()
                viewModel.open(sessionIndex)
                viewModel.delete(sessionIndex)

                assertEquals(emptyList(), repository.list())
                assertIs<SessionTabTarget.NewSession>(viewModel.state.value.activeTab)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}
