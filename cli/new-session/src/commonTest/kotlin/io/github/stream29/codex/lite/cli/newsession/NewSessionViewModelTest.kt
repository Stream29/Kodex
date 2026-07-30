package io.github.stream29.codex.lite.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings
import io.github.stream29.codex.lite.cli.settings.InMemoryCodexGlobalSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val newSessionViewModelTest by testSuite {
    test("settings stay local after a virtual tab is created") {
        coroutineScope {
            val defaultModel = OpenAiModelId("default-model")
            val localModel = OpenAiModelId("local-model")
            val updatedDefaultModel = OpenAiModelId("updated-default-model")
            val updatedHome = Path("updated-codex-home")
            val globalSettings = InMemoryCodexGlobalSettings(
                CodexGlobalSettings(
                    codexHome = Path("codex-home"),
                    newSession = CodexNewSessionSettings(model = defaultModel),
                ),
            )
            val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
            val sessions = createSessionRepositoryViewModel(repository)
            val viewModel = NewSessionViewModel(
                globalSettings = globalSettings,
                sessions = sessions,
                workingDirectory = Path("."),
            )

            try {
                viewModel.updateSettings { current -> current.copy(model = localModel) }

                assertEquals(localModel, viewModel.state.value.settings.model)
                assertEquals(defaultModel, globalSettings.settings.value.newSession.model)

                globalSettings.update { current ->
                    current.copy(
                        codexHome = updatedHome,
                        newSession = current.newSession.copy(model = updatedDefaultModel),
                    )
                }
                val afterGlobalUpdate = withTimeout(5_000) {
                    viewModel.state.first { state -> state.codexHome == updatedHome }
                }

                assertEquals(localModel, afterGlobalUpdate.settings.model)

                val root = viewModel.create()
                val tree = withTimeout(5_000) {
                    root.state.first { state ->
                        state.agents.any { entry -> entry.agentId == state.rootAgentId }
                    }
                }
                val rootAgent = requireNotNull(tree.agents.firstOrNull { entry -> entry.agentId == tree.rootAgentId })
                val createdSettings = withTimeout(5_000) {
                    requireNotNull(
                        rootAgent.viewModel.state.first { state ->
                            state.durable.settings?.model == localModel
                        }.durable.settings,
                    )
                }
                assertEquals(localModel, createdSettings.model)
            } finally {
                viewModel.close()
                sessions.close()
                repository.cancelAndJoin()
            }
        }
    }
}
