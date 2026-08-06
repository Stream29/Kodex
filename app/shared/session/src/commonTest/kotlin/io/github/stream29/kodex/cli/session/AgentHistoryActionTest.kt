package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

val agentHistoryActionTest by testSuite {
    test("revert retains the exact committed entry and removes every later timeline point") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            val root = repository.open(sessionIndex)
            val initialSettings = KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = "Session $sessionIndex",
            )
            root.runtime.modify { storage ->
                storage.initialize(initialSettings)
                storage.stable[1] = StableCleanEvent.UserMessage(
                    listOf(ContentItem.InputText("retained user input")),
                )
                storage.stable[4] = StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("retained assistant output")),
                )
                storage.settings[6] = initialSettings.copy(threadName = "removed title")
                storage.stable[9] = StableCleanEvent.UserMessage(
                    listOf(ContentItem.InputText("removed user input")),
                )
                storage.timestamp[10] = Instant.parse("2026-08-06T00:00:00Z")
            }
            root.runtime.pendingSteer.value = listOf(
                StableCleanEvent.UserMessage(listOf(ContentItem.InputText("removed steer"))),
            )
            val repositoryViewModel = SessionRepositoryViewModel(repository)
            try {
                val rootViewModel = repositoryViewModel.open(sessionIndex)
                val agent = rootViewModel.state.value.agents.single()
                val initialWindow = withTimeout(5.seconds) {
                    agent.historyViewModel.window.first { window ->
                        !window.isLoading && window.entries.any { entry -> entry.index == 9 }
                    }
                }

                agent.viewModel.requestHistoryRevert(storageIndex = 4)
                assertEquals(4, agent.viewModel.pendingHistoryRevert.value?.storageIndex)
                agent.viewModel.dismissHistoryRevert()
                assertNull(agent.viewModel.pendingHistoryRevert.value)

                agent.viewModel.revertHistory(storageIndex = 4)
                val revertedWindow = withTimeout(5.seconds) {
                    agent.historyViewModel.window.first { window ->
                        window.generation > initialWindow.generation && !window.isLoading
                    }
                }

                assertEquals(4, root.storage.latestIndex())
                assertEquals(4, root.storage.stable.floorToIndex(Int.MAX_VALUE))
                assertEquals(0, root.storage.settings.floorToIndex(Int.MAX_VALUE))
                assertNull(root.storage.timestamp.floorToIndex(Int.MAX_VALUE))
                assertEquals(initialSettings, root.storage.settings[4])
                assertTrue(root.runtime.pendingSteer.value.isEmpty())
                assertEquals(listOf(4, 1), revertedWindow.entries.map { entry -> entry.index })

                assertFailsWith<IllegalArgumentException> {
                    agent.viewModel.revertHistory(storageIndex = 9)
                }
                assertNotNull(agent.viewModel.state.value.failureMessage)
            } finally {
                repositoryViewModel.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("revert and fork accept every stable history event category as an exact boundary") {
        coroutineScope {
            val targets = listOf(
                StableCleanEvent.UserMessage(listOf(ContentItem.InputText("user"))),
                StableCleanEvent.AssistantMessage(listOf(ContentItem.OutputText("assistant"))),
                StableCleanEvent.DeveloperMessage(listOf(ContentItem.InputText("developer"))),
                StableCleanEvent.AgentMessage(
                    author = "root",
                    recipient = "child",
                    content = listOf(AgentMessageInputContent.InputText("agent message")),
                ),
                StableCleanEvent.Reasoning(
                    ResponseItem.Reasoning(
                        summary = listOf(
                            ReasoningItemReasoningSummary.SummaryText("reasoning"),
                        ),
                    ),
                ),
                StableTextToolEvent(
                    callId = "call",
                    name = "tool",
                    arguments = JsonObject(emptyMap()),
                    result = "result",
                    success = true,
                ),
                StableCleanEvent.ContextCompaction,
            )

            targets.forEachIndexed { eventPosition, targetEvent ->
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                val sourceIndex = repository.create()
                val source = repository.open(sourceIndex)
                source.runtime.modify { storage ->
                    storage.initialize(
                        KodexAgentSettings(
                            model = OpenAiModelId("test-model"),
                            threadName = "Boundary $eventPosition",
                        ),
                    )
                    storage.stable[1] = StableCleanEvent.UserMessage(
                        listOf(ContentItem.InputText("prefix")),
                    )
                    storage.stable[2] = targetEvent
                    storage.stable[4] = StableCleanEvent.AssistantMessage(
                        listOf(ContentItem.OutputText("suffix")),
                    )
                }
                val repositoryViewModel = SessionRepositoryViewModel(repository)
                try {
                    val sourceViewModel = repositoryViewModel.open(sourceIndex)
                    val sourceAgent = sourceViewModel.state.value.agents.single()

                    val targetViewModel = repositoryViewModel.fork(
                        source = source,
                        untilExclusive = 3,
                    )
                    assertEquals(targetEvent, targetViewModel.rootSession.storage.stable[2])
                    assertEquals(2, targetViewModel.rootSession.storage.stable.floorToIndex(Int.MAX_VALUE))

                    sourceAgent.viewModel.revertHistory(storageIndex = 2)
                    assertEquals(targetEvent, source.storage.stable[2])
                    assertEquals(2, source.storage.latestIndex())
                } finally {
                    repositoryViewModel.close()
                    repository.cancelAndJoin()
                }
            }
        }
    }
}
