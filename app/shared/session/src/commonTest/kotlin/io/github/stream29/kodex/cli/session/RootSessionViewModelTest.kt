package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleSettings
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val rootSessionViewModelTest by testSuite {
    test("updates recursively from child entry snapshots") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
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
                val childViewModel = requireNotNull(childTree.agents.firstOrNull { entry ->
                    entry.parentAgentId == root.storage.id
                }).viewModel

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
                assertTrue(childViewModel.resume().isCancelled)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("closing the root tree propagates through the session scope") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.create())
            val child = root.subagents.open(root.subagents.create())
            val viewModel = RootSessionViewModel(root)
            try {
                viewModel.refresh()

                viewModel.close()

                assertTrue(requireNotNull(root.coroutineContext[Job]).isCancelled)
                assertTrue(requireNotNull(child.coroutineContext[Job]).isCancelled)
            } finally {
                repository.cancelAndJoin()
            }
        }
    }

    test("root submission starts title generation after its input is accepted") {
        coroutineScope {
            val titleModel = OpenAiModelId("title-model")
            val titleRequest = CompletableDeferred<Triple<String, OpenAiModelId, ReasoningEffort>>()
            val responseStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(
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
                    KodexAgentSettings(
                        model = OpenAiModelId("agent-model"),
                        threadName = "Session 0",
                    ),
                )
            }
            val configuration = AgentAutomaticTitleConfiguration(
                generator = SessionTitleGenerator { text, model, reasoningEffort ->
                    titleRequest.complete(Triple(text, model, reasoningEffort))
                    SessionTitleGenerationResult.Generated("Generated root title")
                },
                settingsProvider = {
                    AgentAutomaticTitleSettings(
                        enabled = true,
                        model = titleModel,
                        reasoningEffort = ReasoningEffort.High,
                    )
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
                    Triple("Create a title from this prompt.", titleModel, ReasoningEffort.High),
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

    test("running Agent composer submits pending steer without starting a new turn") {
        coroutineScope {
            val responseStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(
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
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("agent-model")))
            }
            val viewModel = RootSessionViewModel(root)
            try {
                viewModel.refresh()
                val tree = viewModel.state.value
                val rootAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.agentId == tree.rootAgentId
                })
                val initialContent = listOf(ContentItem.InputText("Initial request"))
                val steerContent = listOf(ContentItem.InputText("Adjust the active turn"))

                rootAgent.viewModel.submit(initialContent)
                val runningTurn = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) {
                        responseStarted.await()
                        requireNotNull(root.runtime.runningTurn.value)
                    }
                }
                rootAgent.viewModel.composer.update(
                    text = "Adjust the active turn",
                    cursorOffset = "Adjust the active turn".length,
                )

                assertTrue(rootAgent.viewModel.submitComposer())

                assertEquals("", rootAgent.viewModel.composer.state.value.text)
                assertEquals(
                    listOf(StableCleanEvent.UserMessage(steerContent)),
                    root.runtime.pendingSteer.value,
                )
                assertEquals(StableCleanEvent.UserMessage(initialContent), root.storage.stable[1])
                assertEquals(1, root.runtime.latestIndex.value)

                releaseResponse.complete(Unit)
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) { runningTurn.join() }
                }
            } finally {
                releaseResponse.complete(Unit)
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}
