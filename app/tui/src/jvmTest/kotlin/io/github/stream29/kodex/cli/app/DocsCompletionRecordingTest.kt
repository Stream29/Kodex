package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.cli.settings.UnifiedSettingsStore
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginResult
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Full application approval and auto-opening over a disposable Home and mocked model API. */
val docsCompletionRecordingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("accepting a suggested batch selects cwd and automatically opens created Session tabs") {
        withTimeout(60.seconds) {
            val home = Path(SystemTemporaryDirectory, "kodex-docs-suggestions-${Random.nextLong()}")
            val project = File(home.toString(), "demo-project")
            val firstResponse = MutableStateFlow(true)
            val pending = PendingSuggestSubagentTaskToolEvent(
                "docs-suggestions",
                arguments = SuggestSubagentTaskArgs(listOf(
                    SuggestedSubagentTask("Review tests", "Review disposable sample tests; do not edit files."),
                    SuggestedSubagentTask("Review docs", "Review disposable sample documentation; do not edit files."),
                )),
            )
            val client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                    OpenAiModelId("test-model"), "Test Model",
                    contextWindow = 100_000, maxContextWindow = 100_000,
                )))) }
                createResponse { flow {
                    if (firstResponse.compareAndSet(true, false)) {
                        emit(ResponsesStreamEvent.OutputItemDone(
                            0, pending.toResponseHistoryItems().single() as ResponseItem.ToolCall,
                        ))
                        emit(ResponsesStreamEvent.Completed(Response(id = "pending", endTurn = true)))
                    } else testAnswer()
                } }
            }
            val loginClient = object : OpenAiLoginClient {
                override fun authorizationUrl(request: OpenAiLoginAuthorization) =
                    error("No external login in the docs recording")
                override suspend fun exchangeAuthorizationCode(request: OpenAiAuthorizationCodeExchange):
                    OpenAiLoginResult<OpenAiSubscriptionTokens> =
                    error("No external login in the docs recording")
                override suspend fun refreshSubscriptionTokens(refreshToken: String):
                    OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
                    error("No credential refresh in the docs recording")
            }
            try {
                val prepared = prepareKodexHome(home)
                UnifiedSettingsStore.open(home, defaultBackendSettings()).backendStore.update {
                    it.copy(
                        sessionTitle = it.sessionTitle.copy(enabled = false),
                        newSession = it.newSession.copy(model = OpenAiModelId("test-model")),
                    )
                }
                assertTrue(project.mkdir())
                withKodexApplication(
                    prepared, home, Path(home, "codex"), Path(home, "agents"),
                    applicationWidth = 120,
                    createClient = { client }, createLoginClient = { loginClient },
                ) { app ->
                    val draft = app.viewModel.newSession
                    val revision = draft.composer.update("Please suggest two disposable reviews.")
                    val parent = requireNotNull(app.viewModel.submitNewSession(revision))
                    parent.rename("Review parent")
                    val agent = requireNotNull(parent.rootAgent.value)
                    agent.suggestSubagentTask.state.filterIsInstance<SuggestSubagentTaskState.Pending>().first()
                    val clip = DocsClip("suggestions-accepted", 120, 36)
                    runMosaicTest(MosaicSnapshots) {
                        state.size.value = Terminal.Size(120, 36)
                        setContentAndSnapshot {
                            SessionTreeCliScreen(
                                app.viewModel, app.newLineKey, app.sidebarSettings,
                                onOperationFailure = { throw it },
                            )
                        }
                        clip.add(settle(), "Suggested Sessions")
                        clip.add(settle(), "Review docs")
                        clickLabel(home.toString().takeLast(12))
                        delay(100)
                        clip.add(settle(), "demo-project")
                        clickLabel("demo-project")
                        clip.add(settle(), "Select")
                        clickLabel("[Select]")
                        val selected = agent.suggestSubagentTask.state
                            .filterIsInstance<SuggestSubagentTaskState.Pending>()
                            .first { it.configuration.cwd == Path(project.canonicalPath) }
                        assertEquals(Path(project.canonicalPath), selected.configuration.cwd)
                        clip.add(settle(), "demo-project")
                        clickLabel("Accept")
                        val createdTabs = withTimeout(10.seconds) {
                            app.viewModel.navigation.first { navigation ->
                                navigation.tabs.count { it !== parent } == 2
                            }
                        }.tabs.filter { it !== parent }
                        assertEquals(setOf("Review tests", "Review docs"),
                            createdTabs.map { it.name.first() }.toSet())
                        assertTrue(createdTabs.all { it.settings.value.cwd == Path(project.canonicalPath) })
                        clip.add(settle(), "Review tests")
                        clickLabel("Review tests")
                        val selectedTab = requireNotNull(app.viewModel.navigation.first {
                            it.selectedSession?.name?.value == "Review tests"
                        }.selectedSession)
                        assertTrue(selectedTab in createdTabs)
                        clip.add(settle(), "Review disposable sample tests")
                    }
                    clip.save()
                }
            } finally {
                withContext(NonCancellable) { deleteTestDirectory(home) }
            }
        }
    }
}
