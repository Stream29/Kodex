package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.cli.auth.InMemoryKodexAuthStore
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val sessionTreeCliViewModelTest by testSuite {
    test("updates automatic session-title settings globally") {
        coroutineScope {
            val dependencies = testKodexAgentDependencies()
            val repository = InMemoryKodexSessionRepository(dependencies)
            val globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            )
            val viewModel = SessionTreeCliViewModel(
                repository = createSessionRepositoryViewModel(repository),
                globalSettings = globalSettings,
                workingDirectory = Path("."),
                modelCatalog = dependencies.modelCatalog,
                authStore = InMemoryKodexAuthStore(
                    OpenAiSubscriptionAuthState(accessToken = "test-access-token"),
                ),
                parentScope = this,
            )

            try {
                viewModel.updateSessionTitleSettings { current ->
                    current.copy(
                        enabled = false,
                        model = OpenAiModelId("title-model"),
                        reasoningEffort = ReasoningEffort.High,
                    )
                }

                assertEquals(false, globalSettings.settings.value.sessionTitle.enabled)
                assertEquals(OpenAiModelId("title-model"), globalSettings.settings.value.sessionTitle.model)
                assertEquals(ReasoningEffort.High, globalSettings.settings.value.sessionTitle.reasoningEffort)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("submitting a New session draft persists it and resumes its root runtime") {
        coroutineScope {
            val prompt = "Start the new root session."
            val content = listOf(ContentItem.InputText(prompt))
            val responseStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val dependencies = testKodexAgentDependencies(
                mockOpenAiClient {
                    createResponse {
                        flow {
                            responseStarted.complete(Unit)
                            releaseResponse.await()
                            emit(ResponsesStreamEvent.Completed(Response(id = "agent-response")))
                        }
                    }
                },
            )
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

            try {
                viewModel.initialize()
                val newTarget = assertIs<SessionTabTarget.NewSession>(viewModel.state.value.activeTab)
                val newSession = assertNotNull(viewModel.activeNewSession())
                newSession.composer.update(prompt, prompt.length)

                assertTrue(viewModel.submitNewSessionComposer(newTarget))

                val activeTarget = assertIs<SessionTabTarget.OpenSession>(viewModel.state.value.activeTab)
                val rootTree = withRuntimeTimeout {
                    viewModel.state.first { state ->
                        state.activeTab == activeTarget && state.selectedTree != null
                    }.selectedTree
                }
                val rootAgent = requireNotNull(rootTree?.agents?.firstOrNull { entry ->
                    entry.agentId == rootTree.rootAgentId
                })

                assertEquals(
                    StableCleanEvent.UserMessage(content),
                    rootAgent.viewModel.session.storage.stable[1],
                )
                val runningTurn = withRuntimeTimeout {
                    responseStarted.await()
                    requireNotNull(rootAgent.viewModel.session.runtime.runningTurn.value)
                }
                withRuntimeTimeout {
                    rootAgent.historyViewModel.window.first { window ->
                        window.entries.any { entry ->
                            entry.event == StableCleanEvent.UserMessage(content)
                        }
                    }
                }

                releaseResponse.complete(Unit)
                withRuntimeTimeout { runningTurn.join() }
            } finally {
                releaseResponse.complete(Unit)
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun <T> withRuntimeTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5.seconds) { block() }
    }
