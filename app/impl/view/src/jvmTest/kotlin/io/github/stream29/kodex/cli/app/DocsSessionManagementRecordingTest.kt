package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.testAnswer
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real application shell and persistence, with only the external model API mocked. */
val docsSessionManagementRecordingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("session directory reopens, archives, restores and deletes a disposable Session") {
        withTimeout(60.seconds) {
            val home = Path(SystemTemporaryDirectory, "kodex-docs-session-management-${Random.nextLong()}")
            val client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                    OpenAiModelId("test-model"), "Test Model",
                    contextWindow = 100_000, maxContextWindow = 100_000,
                )))) }
                createResponse { flow { testAnswer() } }
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
                withKodexApplication(
                    prepared, home, Path(home, "codex"), Path(home, "agents"),
                    applicationWidth = 120,
                    createClient = { client }, createLoginClient = { loginClient },
                ) { app ->
                    val draft = app.viewModel.navigation.value.selected as NewSessionViewModel
                    draft.rename("Demo session")
                    val session = requireNotNull(app.viewModel.materializeNewSession(draft))
                    val index = session.sessionIndex
                    val clip = DocsClip("session-management", 120, 36)
                    runMosaicTest(MosaicSnapshots) {
                        state.size.value = Terminal.Size(120, 36)
                        setContentAndSnapshot {
                            SessionTreeCliScreen(
                                app.viewModel, app.newLineKey, app.sidebarSettings,
                                onOperationFailure = { throw it },
                            )
                        }
                        clip.add(settle(), "Demo session")
                        clickLabel("Demo session", MouseEvent.Button.Right)
                        clip.add(settle(), "Rename")
                        clickLabel("Rename")
                        clip.add(settle(), "Rename session")
                        for (char in " renamed") sendKeyEvent(KeyboardEvent(codepoint = char.code))
                        clip.add(settle(), "Demo session renamed")
                        sendKeyEvent(KeyboardEvent(codepoint = 13))
                        settle()
                        assertEquals("Demo session renamed", session.name.value)
                        clip.add(settle(), "Demo session renamed")

                        clickLabel("Demo session renamed", MouseEvent.Button.Right)
                        clip.add(settle(), "Close")
                        clickLabel("Close")
                        assertTrue(app.viewModel.navigation.value.tabs.none {
                            it is PersistedSessionViewModel && it.sessionIndex == index
                        })
                        clip.add(settle(), "Sessions")

                        clickLabel("[Sessions]")
                        clip.add(settle(), "Demo session renamed")
                        clickLabel("Demo session renamed")
                        val reopened = app.viewModel.navigation.first {
                            it.selected is PersistedSessionViewModel &&
                                (it.selected as PersistedSessionViewModel).sessionIndex == index
                        }
                        assertEquals(index, (reopened.selected as PersistedSessionViewModel).sessionIndex)
                        clip.add(settle(), "Demo session renamed")

                        clickLabel("Demo session renamed", MouseEvent.Button.Right)
                        clip.add(settle(), "Close and archive")
                        clickLabel("Close and archive")
                        clip.add(settle(), "Sessions")
                        clickLabel("[Sessions]")
                        clip.add(settle(), "Show archived")
                        clickLabel("Show archived")
                        val catalog = app.viewModel.popup.first {
                            it is io.github.stream29.kodex.app.application.contract.ApplicationPopupState.SessionCatalog
                        } as io.github.stream29.kodex.app.application.contract.ApplicationPopupState.SessionCatalog
                        catalog.viewModel.state.first { state ->
                            state is SessionCatalogState.Loaded &&
                                state.sessions.any { it.sessionIndex == index && it.archived }
                        }
                        clip.add(settle(), "Demo session renamed")
                        clickLabel("Demo session renamed", MouseEvent.Button.Right)
                        clip.add(settle(), "Unarchive")
                        clickLabel("Unarchive")
                        catalog.viewModel.state.first { state ->
                            state is SessionCatalogState.Loaded &&
                                state.sessions.any { it.sessionIndex == index && !it.archived }
                        }
                        clip.add(settle(), "Demo session renamed")

                        clickLabel("Demo session renamed", MouseEvent.Button.Right)
                        clip.add(settle(), "Delete")
                        clickLabel("Delete")
                        clip.add(settle(), "Delete Demo session renamed?")
                        clickLabel("Cancel")
                        clip.add(settle(), "Demo session renamed")
                        assertTrue((catalog.viewModel.state.value as SessionCatalogState.Loaded)
                            .sessions.any { it.sessionIndex == index })
                        clickLabel("Demo session renamed", MouseEvent.Button.Right)
                        clickLabel("Delete")
                        clip.add(settle(), "Delete Demo session renamed?")
                        clickLabel("[Delete]")
                        catalog.viewModel.state.first { state ->
                            state is SessionCatalogState.Loaded &&
                                state.sessions.none { it.sessionIndex == index }
                        }
                        clip.add(settle(), "No persisted sessions")
                    }
                    clip.save()
                }
            } finally {
                withContext(NonCancellable) { deleteTestDirectory(home) }
            }
        }
    }
}
