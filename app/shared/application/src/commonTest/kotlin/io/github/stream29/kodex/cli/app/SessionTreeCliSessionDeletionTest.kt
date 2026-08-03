package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.cli.auth.InMemoryKodexAuthStore
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

val sessionTreeCliSessionDeletionTest by testSuite {
    test("deleting an open session removes its persistent root and tab") {
        coroutineScope {
            val dependencies = testKodexAgentDependencies()
            val repository = InMemoryKodexSessionRepository(dependencies)
            val sessions = createSessionRepositoryViewModel(repository)
            val viewModel = SessionTreeCliViewModel(
                repository = sessions,
                globalSettings = InMemoryKodexGlobalSettings(
                    KodexGlobalSettings(
                        codexHome = Path("codex-home"),
                        newSession = KodexNewSessionSettings(model = OpenAiModelId("test-model")),
                    ),
                ),
                workingDirectory = Path("."),
                modelCatalog = dependencies.modelCatalog,
                authStore = InMemoryKodexAuthStore(
                    OpenAiSubscriptionAuthState(accessToken = "test-access-token"),
                ),
                parentScope = this,
            )
            val sessionIndex = repository.create()
            repository.open(sessionIndex).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
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
