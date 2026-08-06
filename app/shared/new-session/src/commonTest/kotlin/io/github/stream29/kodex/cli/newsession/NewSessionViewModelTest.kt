package io.github.stream29.kodex.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
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
            val initialWorkingDirectory = Path("initial-working-directory")
            val localWorkingDirectory = Path("local-working-directory")
            val globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(
                    codexHome = Path("codex-home"),
                    newSession = KodexNewSessionSettings(model = defaultModel),
                ),
            )
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessions = createSessionRepositoryViewModel(repository)
            val viewModel = NewSessionViewModel(
                globalSettings = globalSettings,
                sessions = sessions,
                workingDirectory = initialWorkingDirectory,
            )

            try {
                viewModel.updateSettings { current -> current.copy(model = localModel) }
                viewModel.updateWorkingDirectory(localWorkingDirectory)

                assertEquals(localModel, viewModel.state.value.settings.model)
                assertEquals(localWorkingDirectory, viewModel.state.value.workingDirectory)
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
                assertEquals(localWorkingDirectory, createdSettings.cwd)
            } finally {
                viewModel.close()
                sessions.close()
                repository.cancelAndJoin()
            }
        }
    }
}
