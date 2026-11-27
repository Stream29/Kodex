package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.utils.mq.InMemoryMqBroker

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.testSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Instant

/** Seed one persisted root and child. No fixture-only renderer, RPC method, or UI actions. */
internal suspend fun CoroutineScope.seedSidebarHistoryIndexFixture(home: Path) {
    prepareKodexHome(home).closeAndJoin()
    val repository = SqliteKodexSessionRepository(home, testKodexAgentDependencies(), InMemoryMqBroker())
    try {
        val sessionId = repository.create()
        val rootId = repository.getRootAgentId(sessionId)
        val root = repository.open(sessionId)
        root.runtime.modify { it.initialize(testSettings("IdxSource", home)) }
        val childId = repository.createChild(rootId).agentId
        checkNotNull(repository.openAgent(childId)).runtime.modify { storage ->
            storage.initialize(testSettings("IdxChild", home))
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("ChildForkPoint"))))
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("ChildTail"))))
        }
        root.runtime.modify { storage ->
            storage.commit(
                StableUserMessage(listOf(ContentItem.InputText("RootScrollTarget"))),
                Instant.parse("2026-10-03T01:02:03Z"),
            )
            storage.commit(StableAgentMessage("FixtureAuthor", "FixtureRecipient", listOf(
                AgentMessageInputContent.InputText("PreviewStart\n" +
                    (1..46).joinToString("\n") { "PreviewLine$it" } + "\nPreviewEnd"),
            )))
            storage.commit(StablePlanUpdate("sidebar-plan", arguments = UpdatePlanArgs(
                "PreviewPlanExplanation",
                listOf(PlanItemArg("PreviewPlanStep", StepStatus.Completed)),
            )))
            storage.commit(StableAskUserEvent(
                askId = 1,
                arguments = RequestUserInputArgs(listOf(
                    RequestUserInputQuestion("credential", "FixtureQuestion", "Enter the fixture credential", isSecret = true),
                )),
                response = RequestUserInputResponse(mapOf(
                    "credential" to RequestUserInputAnswer(listOf("HiddenFixtureAnswerNeverRender")),
                )),
            ))
            storage.commit(StableSubagentCreatedEvent(childId, childName = "IdxChild"))
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("RootForkPoint"))))
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("RootRevertPoint"))))
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("RootAfterRevert"))))
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("RootEditDraft"))))
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("RootTail"))))
        }
    } finally {
        withContext(NonCancellable) { repository.closeAndJoin() }
    }
}
