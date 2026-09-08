package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val agentHistoryActionTest by testSuite {
    test("direct revert uses an exclusive sparse boundary and can remove the first message") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val root = repository.open(index)
            root.runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.index[2] = userMessage("first")
                storage.settings[4] = storage.settings[0].copy(threadName = "Between messages")
                storage.index[8] = userMessage("edit me")
                storage.index[12] = StableAssistantMessage(listOf(ContentItem.OutputText("discard")))
            }
            val store = testSessionViewModelRegistry(repository, this)
            val agent = store.open(index).rootAgent
            try {
                val generation = agent.history.awaitStorageIndex(12)
                agent.revertHistory(untilExclusive = 8, expectedGeneration = generation)
                assertEquals(4, root.storage.latestIndex())
                assertEquals(listOf(2), root.storage.index.indexesIn(0..20))
                assertEquals("Between messages", root.storage.settings[4].threadName)
                assertIs<AgentHistoryActionState.None>(agent.historyAction.value)

                val nextGeneration = withTimeout(5.seconds) {
                    agent.history.historyItems.first { it.generation > generation }.generation
                }
                assertFailsWith<IllegalArgumentException> {
                    agent.revertHistory(2, generation)
                }
                assertFailsWith<IllegalArgumentException> {
                    agent.revertHistory(0, nextGeneration)
                }
                assertFailsWith<IllegalArgumentException> {
                    agent.revertHistory(6, nextGeneration)
                }
                // Index 1 has no history row: a boundary is not a selected history item.
                agent.revertHistory(untilExclusive = 1, expectedGeneration = nextGeneration)
                assertTrue(root.storage.index.indexesIn(0..20).isEmpty())
                assertEquals(0, root.storage.latestIndex())
                assertEquals(OpenAiModelId("test-model"), root.storage.settings[0].model)
                assertEquals(0L, root.storage.tokenCount[0])
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("fork accepts a sparse boundary but rejects a foreign owner and stale generation") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val store = testSessionViewModelRegistry(repository, this)
            val session = store.create { KodexAgentSettings(model = OpenAiModelId("test-model")) }
            val root = repository.open(session.sessionIndex)
            root.runtime.modify { storage ->
                storage.index[2] = userMessage("keep")
                storage.index[8] = userMessage("discard")
            }
            val other = store.create { KodexAgentSettings(model = OpenAiModelId("test-model")) }
            val agent = session.rootAgent
            try {
                val generation = agent.history.awaitStorageIndex(8)
                assertFailsWith<IllegalArgumentException> { session.fork(other.rootAgent, 5, generation) }
                assertFailsWith<IllegalArgumentException> { session.fork(agent, 5, generation + 1) }
                assertFailsWith<IllegalArgumentException> { session.fork(agent, 0, generation) }
                assertFailsWith<IllegalArgumentException> { session.fork(agent, 10, generation) }
                val fork = repository.open(session.fork(agent, 5, generation))
                assertEquals(listOf(2), fork.storage.index.indexesIn(0..20))
                assertEquals(listOf(2, 8), root.storage.index.indexesIn(0..20))
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("direct revert survives cancellation of its frontend waiter") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val store = testSessionViewModelRegistry(repository, this)
            val session = store.create { KodexAgentSettings(model = OpenAiModelId("test-model")) }
            val root = repository.open(session.sessionIndex)
            root.runtime.modify { storage ->
                storage.index[2] = userMessage("remove")
            }
            val agent = session.rootAgent
            try {
                val generation = agent.history.awaitStorageIndex(2)
                val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.revertHistory(2, generation)
                    awaitCancellation()
                }
                waiter.cancelAndJoin()
                withTimeout(5.seconds) { root.runtime.latestIndex.first { it == 0 } }
                assertTrue(root.storage.index.indexesIn(0..20).isEmpty())
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("revert is bound to the current history generation") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val root = repository.open(index)
            root.runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.index[2] = userMessage("retain")
                storage.index[4] = StableAssistantMessage(
                    listOf(ContentItem.OutputText("remove")),
                )
            }
            val store = testSessionViewModelRegistry(repository, this)
            val session = store.open(index)
            val agent = session.rootAgent
            try {
                val initialGeneration = agent.history.awaitStorageIndex(2)
                val requestId = agent.requestHistoryRevert(
                    untilExclusive = 3, expectedGeneration = initialGeneration,
                )

                agent.confirmHistoryRevert(requestId)

                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        root.runtime.latestIndex.first { latestIndex -> latestIndex == 2 }
                    }
                }
                assertEquals(2, root.storage.latestIndex())
                val currentGeneration = withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        agent.history.historyItems.first { window ->
                            window.generation > initialGeneration
                        }.generation
                    }
                }
                agent.history.awaitStorageIndex(2)
                root.runtime.modify { storage ->
                    storage.index[3] = StableAssistantMessage(
                        listOf(ContentItem.OutputText("invalidate")),
                    )
                }
                val changedGeneration = agent.history.awaitStorageIndex(3)
                assertEquals(currentGeneration, changedGeneration)
                agent.history.awaitStorageIndex(2)
                val staleRequest = agent.requestHistoryRevert(
                    untilExclusive = 3, expectedGeneration = currentGeneration,
                )
                root.runtime.modify { storage ->
                    storage.revert(2)
                }
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        agent.history.historyItems.first { window ->
                            window.generation > currentGeneration
                        }
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
                storage.index[2] = userMessage("fork")
            }
            val store = testSessionViewModelRegistry(repository, this)
            val session = store.open(index)
            try {
                val generation = session.rootAgent.history.awaitStorageIndex(2)
                val forkIndex = session.fork(
                    session.rootAgent,
                    untilExclusive = 3, expectedGeneration = generation,
                )

                assertEquals(listOf(index, forkIndex), repository.list())
                assertEquals("[fork] Source", repository.open(forkIndex).storage.settings[3].threadName)
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
                storage.index[2] = userMessage("retain")
                storage.index[4] = StableAssistantMessage(
                    listOf(ContentItem.OutputText("remove")),
                )
            }
            val store = testSessionViewModelRegistry(repository, this)
            val agent = store.open(index).rootAgent
            try {
                val generation = agent.history.awaitStorageIndex(2)
                val requestId = agent.requestHistoryRevert(
                    untilExclusive = 3, expectedGeneration = generation,
                )
                val frontendCaller = launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.confirmHistoryRevert(requestId)
                    awaitCancellation()
                }

                assertIs<AgentHistoryActionState.None>(agent.historyAction.value)
                frontendCaller.cancelAndJoin()
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        root.runtime.latestIndex.first { latestIndex -> latestIndex == 2 }
                    }
                }
                assertEquals(2, root.storage.latestIndex())
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun userMessage(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private suspend fun AgentHistoryViewModel.awaitStorageIndex(storageIndex: Int): Long =
    withContext(Dispatchers.Default) {
        // A context action targets a materialized row, not an arbitrary stored index.
        requestScrollToStorageIndex(storageIndex)
        withTimeout(5.seconds) {
            historyItems.first { window ->
                contains(window.generation, storageIndex)
            }.generation
        }
    }
