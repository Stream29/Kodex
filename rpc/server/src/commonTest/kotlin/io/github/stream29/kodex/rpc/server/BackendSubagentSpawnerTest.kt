package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SubagentCreationMode
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.SessionSpawnSource
import io.github.stream29.kodex.rpc.models.SubagentSpawnRejection
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.rpc.models.SubagentSpawnStage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.test.*

val backendSubagentSpawnerTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("spawn is bound to a live owner and creates new history at each level") {
        val client = mockOpenAiClient { createResponse { flow { awaitCancellation() } } }
        runtimeFixture(client) { repository, rootId, _ ->
            val repo = repository as SqliteKodexSessionRepository
            val bus = AgentEventBus(repo)
            val rpc = BackendAgentRuntimeRpc(repo, subagentSpawner = BackendSubagentSpawner(
                repo, { emptyList() }, repo::createChild, repo::discardUninitializedChild,
                bus::recordSubagentCreation, bus::submitUserMessage,
            ))
            val root = checkNotNull(repo.openAgent(rootId))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("root-private")))
            val request = SubagentSpawnRequest(SessionSpawnSource(rootId, root.runtime.invocationId), "first-task", "Review")
            assertEquals(
                SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource),
                rpc.spawnSubagent(request.copy(source = request.source.copy(invocationId = "stale"))),
            )
            assertEquals(
                SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidMessage),
                rpc.spawnSubagent(request.copy(message = " ")),
            )
            assertEquals(
                SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidConfiguration),
                rpc.spawnSubagent(request.copy(name = " ")),
            )
            val settings = root.storage.getSettingsAt(Int.MAX_VALUE)
            assertTrue(rpc.compareAndSetSettings(rootId, settings, settings.copy(subagentCreationMode = SubagentCreationMode.Reject)))
            assertEquals(SubagentSpawnResult.Rejected(SubagentSpawnRejection.CreationRejected), rpc.spawnSubagent(request))
            assertTrue(rpc.compareAndSetSettings(rootId, settings.copy(subagentCreationMode = SubagentCreationMode.Reject), settings))
            val child = assertIs<SubagentSpawnResult.Created>(rpc.spawnSubagent(request))
            val childOwner = checkNotNull(repo.openAgent(child.agentId))
            val grandchild = assertIs<SubagentSpawnResult.Created>(rpc.spawnSubagent(SubagentSpawnRequest(
                SessionSpawnSource(child.agentId, childOwner.runtime.invocationId), "second-task", "Check",
            )))
            assertEquals(rootId, child.parentAgentId)
            assertEquals(child.agentId, grandchild.parentAgentId)
            assertEquals(child.sessionId, grandchild.sessionId)
            assertNotEquals(root.runtime.invocationId, childOwner.runtime.invocationId)
            for ((id, task) in listOf(child.agentId to "first-task", grandchild.agentId to "second-task")) {
                rpc.getRunningTurnFlow(id).first { it }
                val owner = checkNotNull(repo.openAgent(id))
                val messages = setOf(AgentCommitType.UserMessage)
                owner.runtime.latestIndex.first { index -> owner.storage.countCommitsIn(0..index, messages) != 0 }
                assertEquals(listOf(ContentItem.InputText(task)), assertIs<StableUserMessage>(
                    owner.storage.getFirstCommitsIn(0..Int.MAX_VALUE, messages, 2).single().commit,
                ).content)
            }
            val rootEvent = root.storage.getLastCommitsIn(
                0..Int.MAX_VALUE, setOf(AgentCommitType.EventSubagentCreated), 1,
            ).single().commit
            assertEquals(StableSubagentCreatedEvent(child.agentId, "Review", "first-task"), rootEvent)
            assertEquals("Review", childOwner.storage.getSettingsAt(Int.MAX_VALUE).name)
            val childEvent = childOwner.storage.getLastCommitsIn(
                0..Int.MAX_VALUE, setOf(AgentCommitType.EventSubagentCreated), 1,
            ).single().commit
            assertEquals(StableSubagentCreatedEvent(grandchild.agentId, "Check", "second-task"), childEvent)
            val instructions = checkNotNull(repo.openAgent(grandchild.agentId)).storage.getSettingsAt(Int.MAX_VALUE).instructions
            assertContains(instructions, "$rootId → ${child.agentId} → ${grandchild.agentId}")
        }
    }

    test("spawn retains the known child on either partial failure") {
        runtimeFixture { repository, rootId, _ ->
            val repo = repository as SqliteKodexSessionRepository
            val bus = AgentEventBus(repo)
            val root = checkNotNull(repo.openAgent(rootId))
            val request = SubagentSpawnRequest(SessionSpawnSource(rootId, root.runtime.invocationId), "task", "Partial")
            for (stage in SubagentSpawnStage.entries) {
                var submitCalled = false
                val rpc = BackendAgentRuntimeRpc(repo, subagentSpawner = BackendSubagentSpawner(
                    repo, { emptyList() }, repo::createChild, repo::discardUninitializedChild,
                    { parent, event ->
                        if (stage == SubagentSpawnStage.Record) error("Fixture record failure")
                        bus.recordSubagentCreation(parent, event)
                    },
                    { _, _ ->
                        submitCalled = true
                        error("Fixture input failure")
                    },
                ))
                val result = assertIs<SubagentSpawnResult.Partial>(rpc.spawnSubagent(request))
                assertEquals(stage, result.stage)
                assertEquals(rootId, result.parentAgentId)
                val stored = assertNotNull(repo.getAgentRecord(result.agentId))
                assertEquals(result.sessionId, stored.sessionId)
                assertEquals(rootId, stored.parentAgentId)
                assertEquals(stage == SubagentSpawnStage.Submit, submitCalled)
            }
        }
    }
}
