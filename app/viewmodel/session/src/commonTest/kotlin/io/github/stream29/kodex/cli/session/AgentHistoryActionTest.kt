package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowStatus
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

val agentHistoryActionTest by testSuite {
    test("revert is bound to the current history generation") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val root = repository.open(index)
            root.runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.stable[1] = userMessage("retain")
                storage.stable[4] = StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("remove")),
                )
            }
            val store = testSessionViewModelRegistry(repository, this)
            val session = store.open(index)
            val agent = session.rootAgent
            try {
                val window = withTimeout(5.seconds) {
                    agent.history.window.first { state ->
                        state.status is AgentHistoryWindowStatus.Ready &&
                            state.entries.any { entry -> entry.key.primaryStorageIndex == 4 }
                    }
                }
                val requestId = agent.requestHistoryRevert(
                    AgentHistoryTarget(window.generation, storageIndex = 1),
                )

                agent.confirmHistoryRevert(requestId)

                withTimeout(5.seconds) {
                    root.runtime.latestIndex.first { latestIndex -> latestIndex == 1 }
                }
                assertEquals(1, root.storage.latestIndex())
                val currentWindow = withTimeout(5.seconds) {
                    agent.history.window.first { state ->
                        state.generation > window.generation &&
                            state.entries.any { entry -> entry.key.primaryStorageIndex == 1 }
                    }
                }
                root.runtime.modify { storage ->
                    storage.stable[2] = StableCleanEvent.AssistantMessage(
                        listOf(ContentItem.OutputText("invalidate")),
                    )
                }
                val changedWindow = withTimeout(5.seconds) {
                    agent.history.window.first { state ->
                        state.entries.any { entry -> entry.key.primaryStorageIndex == 2 }
                    }
                }
                assertEquals(currentWindow.generation, changedWindow.generation)
                val staleRequest = agent.requestHistoryRevert(
                    AgentHistoryTarget(currentWindow.generation, storageIndex = 1),
                )
                root.runtime.modify { storage ->
                    storage.revert(2)
                }
                withTimeout(5.seconds) {
                    agent.history.window.first { state ->
                        state.generation > currentWindow.generation
                    }
                }
                assertFailsWith<IllegalArgumentException> {
                    agent.confirmHistoryRevert(staleRequest)
                }
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("fork belongs to its persisted session and does not change navigation") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val root = repository.open(index)
            root.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Source",
                    ),
                )
                storage.stable[1] = userMessage("fork")
            }
            val store = testSessionViewModelRegistry(repository, this)
            val session = store.open(index)
            try {
                val window = withTimeout(5.seconds) {
                    session.rootAgent.history.window.first { state ->
                        state.status is AgentHistoryWindowStatus.Ready &&
                            state.entries.any { entry -> entry.key.primaryStorageIndex == 1 }
                    }
                }
                val forkIndex = session.fork(
                    session.rootAgent,
                    AgentHistoryTarget(window.generation, storageIndex = 1),
                )

                assertEquals(listOf(index, forkIndex), repository.list())
                assertEquals("[fork] Source", repository.open(forkIndex).storage.settings[2].threadName)
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("accepted revert survives cancellation of its frontend caller") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val root = repository.open(index)
            root.runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.stable[1] = userMessage("retain")
                storage.stable[4] = StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("remove")),
                )
            }
            val store = testSessionViewModelRegistry(repository, this)
            val agent = store.open(index).rootAgent
            try {
                val window = withTimeout(5.seconds) {
                    agent.history.window.first { state ->
                        state.status is AgentHistoryWindowStatus.Ready &&
                            state.entries.any { entry -> entry.key.primaryStorageIndex == 4 }
                    }
                }
                val requestId = agent.requestHistoryRevert(
                    AgentHistoryTarget(window.generation, storageIndex = 1),
                )
                val frontendCaller = launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.confirmHistoryRevert(requestId)
                    awaitCancellation()
                }

                assertIs<AgentHistoryActionState.None>(agent.historyAction.value)
                frontendCaller.cancelAndJoin()
                withTimeout(5.seconds) {
                    root.runtime.latestIndex.first { latestIndex -> latestIndex == 1 }
                }
                assertEquals(1, root.storage.latestIndex())
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(listOf(ContentItem.InputText(text)))
