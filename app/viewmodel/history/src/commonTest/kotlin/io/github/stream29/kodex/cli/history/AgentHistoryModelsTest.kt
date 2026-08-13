package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.history.contract.AgentHistoryEdgeState
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadRequest
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowStatus
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

val agentHistoryModelsTest by testSuite {
    test("loads finite newest-first windows across sparse indexes") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("one")
                storage.stable[4] = userMessage("four")
                storage.stable[9] = userMessage("nine")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                val initial = withTimeout(5.seconds) {
                    model.window.first { window ->
                        window.status is AgentHistoryWindowStatus.Ready &&
                            window.entries.size == 3
                    }
                }
                assertEquals(
                    listOf(9, 4, 1),
                    initial.entries.map { entry -> entry.key.primaryStorageIndex },
                )
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("rejects stale paging cursors after history invalidation") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(70) { position ->
                    storage.stable[position + 1] = userMessage("$position")
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                val initial = withTimeout(5.seconds) {
                    model.window.first { window ->
                        window.status is AgentHistoryWindowStatus.Ready &&
                            window.olderEdge is AgentHistoryEdgeState.Ready
                    }
                }
                val ready = assertIs<AgentHistoryEdgeState.Ready>(initial.olderEdge)

                runtime.modify { storage -> storage.revert(10) }
                val invalidated = withTimeout(5.seconds) {
                    model.window.first { window ->
                        window.generation > initial.generation &&
                            window.status is AgentHistoryWindowStatus.Ready
                    }
                }
                model.request(
                    AgentHistoryLoadRequest(
                        cursor = ready.cursor,
                        itemBudget = 16,
                    ),
                )

                assertEquals(invalidated, model.window.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )
