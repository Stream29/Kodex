package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.seedTestHistory
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginResult
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A menu/dialog becomes unavailable rather than retaining a clickable stale action. */
val docsHistoryInvalidationRecordingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("running and no-longer-selected history targets dismiss actions") {
        withTimeout(60.seconds) {
            val home = Path(SystemTemporaryDirectory, "kodex-docs-history-invalidation-${Random.nextLong()}")
            val gate = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                    OpenAiModelId("test-model"), "Test Model",
                    contextWindow = 100_000, maxContextWindow = 100_000,
                )))) }
                createResponse { flow { gate.await(); testAnswer() } }
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
                val sourceIndex = seedTestHistory(home, "History safety example", (2..4).associateWith {
                    StableUserMessage(listOf(ContentItem.InputText("Offline history entry $it")))
                })
                withKodexApplication(
                    prepared, home, Path(home, "codex"), Path(home, "agents"),
                    applicationWidth = 120,
                    createClient = { client }, createLoginClient = { loginClient },
                ) { app ->
                    val draft = requireNotNull(app.viewModel.navigation.value.selected)
                    val session = app.viewModel.openSession(sourceIndex)
                    val agent = requireNotNull(session.rootAgent.value)
                    val clip = DocsClip("history-invalidation", 120, 36)
                    runMosaicTest(MosaicSnapshots) {
                        state.size.value = Terminal.Size(120, 36)
                        setContentAndSnapshot {
                            SessionTreeCliScreen(
                                app.viewModel, app.newLineKey, app.sidebarSettings,
                                onOperationFailure = { throw it },
                            )
                        }
                        clip.add(settle(), "Offline history entry 4")
                        clickLabel("Offline history entry 3", MouseEvent.Button.Right)
                        clip.add(settle(), "Revert to here")
                        agent.submit(listOf(ContentItem.InputText("Disposable running example")))
                        withTimeout(5.seconds) { agent.running.first { it } }
                        val running = settle()
                        clip.add(running, "Stop")
                        assertFalse("Revert to here" in running.draw().render(AnsiLevel.NONE, false))
                        gate.complete(Unit)
                        withTimeout(5.seconds) { agent.running.first { !it } }
                        repeat(3) { delay(20); settle() }
                        clickLabel("Offline history entry 3", MouseEvent.Button.Right)
                        clip.add(settle(), "Revert to here")
                        clickLabel("Revert to here")
                        clip.add(settle(), "Revert history to here?")
                        assertTrue(app.viewModel.selectTab(draft))
                        val invalidated = settle()
                        clip.add(invalidated, "New Session")
                        assertFalse("Revert history to here?" in invalidated.draw().render(AnsiLevel.NONE, false))
                        assertIs<AgentHistoryActionState.None>(agent.historyAction.value)
                    }
                    clip.save()
                }
            } finally {
                gate.complete(Unit)
                withContext(NonCancellable) { deleteTestDirectory(home) }
            }
        }
    }
}
