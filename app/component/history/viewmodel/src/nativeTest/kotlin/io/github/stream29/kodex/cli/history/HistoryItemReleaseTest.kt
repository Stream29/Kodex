package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.time.Duration

val historyItemReleaseTest by testSuite {
    for (kind in listOf(
        HistoryItemKind.Message,
        HistoryItemKind.PlanUpdate,
        HistoryItemKind.RequestUserInput,
        HistoryItemKind.SuggestSubagentTask,
    )) {
        test("leaf $kind initializes synchronously without spawning background jobs") {
            coroutineScope {
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                val itemOwner = supervisorChildScope()
                try {
                    val session = repository.open(repository.create())
                    val context = HistoryItemLoadContext(
                        AgentHistorySource(session.storage, session.runtime.latestIndex, session.runtime.state),
                        itemOwner, { true },
                    )
                    val event = when (kind) {
                        HistoryItemKind.Message -> StableUserMessage(listOf(ContentItem.InputText("prompt")))
                        HistoryItemKind.PlanUpdate -> StablePlanUpdate(
                            callId = "call",
                            arguments = UpdatePlanArgs(plan = emptyList()),
                        )
                        HistoryItemKind.RequestUserInput -> StableRequestUserInputToolEvent(
                            callId = "call",
                            arguments = RequestUserInputArgs(questions = emptyList()),
                            result = StableRequestUserInputResult.Failure("none"),
                        )
                        HistoryItemKind.SuggestSubagentTask -> StableSuggestSubagentTaskToolEvent(
                            callId = "call",
                            arguments = SuggestSubagentTaskArgs(tasks = emptyList()),
                            result = StableSuggestSubagentTaskResult.Failure("none"),
                        )
                        else -> error("Not a leaf kind")
                    }
                    val descriptor = HistoryItemDescriptor(
                        1, HistoryItemSource.Index, kind, Duration.ZERO, event,
                    )
                    val item: HistoryItemViewModel = when (kind) {
                        HistoryItemKind.Message -> MessageHistoryItemViewModelImpl(1, descriptor, context)
                        HistoryItemKind.PlanUpdate -> PlanUpdateHistoryItemViewModelImpl(1, descriptor, context)
                        HistoryItemKind.RequestUserInput -> RequestUserInputHistoryItemViewModelImpl(1, descriptor, context)
                        HistoryItemKind.SuggestSubagentTask ->
                            SuggestSubagentTaskHistoryItemViewModelImpl(1, descriptor, context)
                    }
                    val ownerJob = requireNotNull(itemOwner.coroutineContext[Job])
                    assertEquals(0, ownerJob.children.count())
                    item.release()
                    item.ensureLoaded()
                    assertEquals(0, ownerJob.children.count())
                } finally {
                    itemOwner.cancelAndJoin()
                    repository.cancelAndJoin()
                }
            }
        }
    }
}
