package io.github.stream29.codex.lite.cli.app

import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentsession.test.testCodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.cli.session.RootSessionViewModel
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SessionAgentSidebarTest {
    @Test
    fun nestedAgentsAreLazilyExpandedAndUseTheirLastPathSegment() = runTest {
        coroutineScope {
            val repository = InMemoryCodexSessionRepository(testCodexAgentDependencies())
            val root = repository.open(repository.create())
            val worker = root.subagents.open(root.subagents.create())
            val reviewer = worker.subagents.open(worker.subagents.create())
            root.initialize("Root / title")
            worker.initialize("/root/worker")
            reviewer.initialize("/root/worker/reviewer")
            val viewModel = RootSessionViewModel(root)

            try {
                viewModel.refresh()
                val tree = withTimeout(5.seconds) {
                    viewModel.state.first { state ->
                        state.agents.size == 3 && state.agents.all { entry ->
                            entry.viewModel.state.value.durable.settings != null
                        }
                    }
                }
                val rootAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.agentId == tree.rootAgentId
                })
                val workerAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.parentAgentId == rootAgent.agentId
                })
                val reviewerAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.parentAgentId == workerAgent.agentId
                })

                assertEquals(
                    listOf(rootAgent.agentId, workerAgent.agentId),
                    tree.visibleAgentTreeEntries(setOf(tree.rootAgentId)).map { entry -> entry.agentId },
                )
                assertEquals(
                    listOf(rootAgent.agentId, workerAgent.agentId, reviewerAgent.agentId),
                    tree.visibleAgentTreeEntries(setOf(tree.rootAgentId, workerAgent.agentId))
                        .map { entry -> entry.agentId },
                )
                assertEquals("Root / title", tree.agentTreeNodeLabel(rootAgent, "Root / title"))
                assertEquals("worker", tree.agentTreeNodeLabel(workerAgent, "/root/worker"))
                assertEquals("reviewer", tree.agentTreeNodeLabel(reviewerAgent, "/root/worker/reviewer"))
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun CodexAgentSession.initialize(threadName: String) {
    runtime.modify { storage ->
        storage.initialize(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = threadName,
            ),
        )
    }
}
