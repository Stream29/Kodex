package io.github.stream29.codex.lite.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.cli.auth.InMemoryCodexAuthStore
import io.github.stream29.codex.lite.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings
import io.github.stream29.codex.lite.cli.settings.InMemoryCodexGlobalSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
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
            val dependencies = testCodexAgentDependencies()
            val repository = InMemoryCodexSessionRepository(dependencies)
            val globalSettings = InMemoryCodexGlobalSettings(
                CodexGlobalSettings(codexHome = Path("codex-home")),
            )
            val viewModel = SessionTreeCliViewModel(
                repository = createSessionRepositoryViewModel(repository),
                globalSettings = globalSettings,
                workingDirectory = Path("."),
                modelCatalog = dependencies.modelCatalog,
                authStore = InMemoryCodexAuthStore(
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
            val dependencies = testCodexAgentDependencies(
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
