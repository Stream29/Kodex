package io.github.stream29.kodex.cli.newsession

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleSettings
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val newSessionViewModelTest by testSuite {
    test("settings stay local and materialization captures one exact snapshot") {
        coroutineScope {
            val workingDirectory = Path(
                SystemTemporaryDirectory,
                "kodex-new-session-${Random.nextLong()}",
            ).also { directory ->
                kotlinx.io.files.SystemFileSystem.createDirectories(directory)
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(
                    mockOpenAiClient {
                        createResponse {
                            val assistant = ResponseItem.Message(
                                id = ResponseItemId("assistant-message"),
                                role = MessageRole.Assistant,
                                content = emptyList(),
                            )
                            flowOf(
                                ResponsesStreamEvent.OutputItemAdded(
                                    outputIndex = 0,
                                    item = assistant,
                                ),
                                ResponsesStreamEvent.OutputItemDone(
                                    outputIndex = 0,
                                    item = assistant,
                                ),
                                ResponsesStreamEvent.Completed(
                                    Response(id = "response", endTurn = true),
                                ),
                            )
                        }
                    },
                ),
            )
            val store = testSessionViewModelRegistry(repository, this)
            val model = NewSessionViewModelImpl(
                arguments = NewSessionViewModelArguments(
                    defaultName = DEFAULT_NEW_SESSION_NAME,
                    initialSettings = KodexAgentSettings(
                        model = OpenAiModelId("default-model"),
                        cwd = Path("."),
                    ),
                ),
                sessions = store,
                composerFactory = DefaultComposerViewModelFactory,
                models = MutableStateFlow(emptyList()),
            )
            try {
                model.updateModel(OpenAiModelId("local-model"))
                model.updateWorkingDirectory(workingDirectory)
                model.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                model.composer.update("Start this session", "Start this session".length)

                val persisted = model.materialize()

                assertEquals(OpenAiModelId("local-model"), persisted.settings.value.model)
                assertEquals(workingDirectory, persisted.settings.value.cwd)
                assertEquals(
                    RequestUserInputMode.NoQuestion,
                    persisted.settings.value.requestUserInputMode,
                )
                assertEquals(
                    ContentItem.InputText("Start this session"),
                    repository.open(persisted.sessionIndex).storage.index[2]
                        .let { event -> (event as StableUserMessage).content.single() },
                )
                assertTrue(model.composer.state.value.text.isEmpty())
                assertFailsWith<IllegalStateException> { model.materialize() }

            } finally {
                model.close()
                store.shutdown()
                repository.cancelAndJoin()
                kotlinx.io.files.SystemFileSystem.delete(workingDirectory, mustExist = false)
            }
        }
    }

    test("numbered draft keeps its display label but materializes an auto-title eligible name") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(
                    mockOpenAiClient {
                        createResponse {
                            flowOf(
                                ResponsesStreamEvent.Completed(
                                    Response(id = "response", endTurn = true),
                                ),
                            )
                        }
                    },
                ),
            )
            val generatedTitle = "Restore automatic session titles"
            val generatedFrom = MutableStateFlow<String?>(null)
            val allowGeneratedTitle = CompletableDeferred<Unit>()
            val automaticTitles = AgentAutomaticTitleConfiguration(
                generator = { userText, _, _ ->
                    generatedFrom.value = userText
                    allowGeneratedTitle.await()
                    SessionTitleGenerationResult.Generated(generatedTitle)
                },
                settingsProvider = {
                    AgentAutomaticTitleSettings(
                        enabled = true,
                        model = null,
                    )
                },
            )
            val store = testSessionViewModelRegistry(
                repository = repository,
                scope = this,
                automaticTitleConfiguration = automaticTitles,
            )
            val model = NewSessionViewModelImpl(
                arguments = NewSessionViewModelArguments(
                    defaultName = "New Session 2",
                    initialSettings = KodexAgentSettings(
                        model = OpenAiModelId("default-model"),
                        cwd = Path("."),
                    ),
                ),
                sessions = store,
                composerFactory = DefaultComposerViewModelFactory,
                models = MutableStateFlow(emptyList()),
            )
            try {
                assertEquals("New Session 2", model.name.value)
                model.composer.update("Fix automatic titles", "Fix automatic titles".length)

                val persisted = model.materialize()

                withTimeout(5.seconds) {
                    generatedFrom.first { userText -> userText != null }
                }
                assertEquals(
                    "Session ${persisted.sessionIndex}",
                    persisted.settings.value.threadName,
                )
                allowGeneratedTitle.complete(Unit)
                withTimeout(5.seconds) {
                    persisted.name.first { name -> name == generatedTitle }
                }
                assertEquals("Fix automatic titles", generatedFrom.value)
                assertEquals(generatedTitle, persisted.settings.value.threadName)
            } finally {
                model.close()
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }
}
