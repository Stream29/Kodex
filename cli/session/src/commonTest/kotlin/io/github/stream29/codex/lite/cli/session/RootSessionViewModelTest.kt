package io.github.stream29.codex.lite.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.codex.lite.cli.agent.AgentAutomaticTitleSettings
import io.github.stream29.codex.lite.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.codex.lite.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.OpenAiModelId
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
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals

val rootSessionViewModelTest by testSuite {
    test("updates recursively from child entry snapshots") {
        coroutineScope {
            val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
            val root = repository.open(repository.create())
            val viewModel = RootSessionViewModel(root)
            try {
                viewModel.refresh()
                assertEquals(1, viewModel.state.value.agents.size)

                val childEntry = root.subagents.create()
                val childTree = withTimeout(5_000) {
                    viewModel.state.first { tree -> tree.agents.size == 2 }
                }
                assertEquals(listOf(0, 1), childTree.agents.map(AgentRuntimeTreeEntry::depth))

                val child = root.subagents.open(childEntry)
                child.subagents.create()
                val nestedTree = withTimeout(5_000) {
                    viewModel.state.first { tree -> tree.agents.size == 3 }
                }
                assertEquals(listOf(0, 1, 2), nestedTree.agents.map(AgentRuntimeTreeEntry::depth))

                root.subagents.delete(childEntry)
                val removedTree = withTimeout(5_000) {
                    viewModel.state.first { tree -> tree.agents.size == 1 }
                }
                assertEquals(root.storage.id, removedTree.agents.single().agentId)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("root submission starts title generation after its input is accepted") {
        coroutineScope {
            val titleModel = OpenAiModelId("title-model")
            val titleRequest = CompletableDeferred<Pair<String, OpenAiModelId>>()
            val responseStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val repository = InMemoryCodexSessionRepository(
                testCodexAgentDependencies(
                    mockOpenAiClient {
                        createResponse {
                            flow {
                                responseStarted.complete(Unit)
                                releaseResponse.await()
                                emit(ResponsesStreamEvent.Completed(Response(id = "agent-response")))
                            }
                        }
                    },
                ),
            )
            val root = repository.open(repository.create())
            root.runtime.modify { storage ->
                storage.initialize(
                    CodexAgentSettings(
                        model = OpenAiModelId("agent-model"),
                        threadName = "Session 0",
                    ),
                )
            }
            val configuration = AgentAutomaticTitleConfiguration(
                generator = SessionTitleGenerator { text, model ->
                    titleRequest.complete(text to model)
                    SessionTitleGenerationResult.Generated("Generated root title")
                },
                settingsProvider = {
                    AgentAutomaticTitleSettings(enabled = true, model = titleModel)
                },
            )
            val viewModel = RootSessionViewModel(root, configuration)
            try {
                viewModel.refresh()
                val tree = viewModel.state.value
                val rootAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.agentId == tree.rootAgentId
                })
                val content = listOf(ContentItem.InputText("Create a title from this prompt."))

                rootAgent.viewModel.submit(content)

                val runningTurn = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) {
                        responseStarted.await()
                        requireNotNull(root.runtime.runningTurn.value)
                    }
                }
                assertEquals(
                    "Create a title from this prompt." to titleModel,
                    withContext(Dispatchers.Default.limitedParallelism(1)) {
                        withTimeout(5.seconds) {
                            titleRequest.await()
                        }
                    },
                )
                releaseResponse.complete(Unit)
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) { runningTurn.join() }
                }
                val titleIndex = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) {
                        root.runtime.latestIndex.first { index ->
                            root.storage.settings[index].threadName == "Generated root title"
                        }
                    }
                }
                assertEquals("Generated root title", root.storage.settings[titleIndex].threadName)
            } finally {
                releaseResponse.complete(Unit)
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}
