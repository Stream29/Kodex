package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

val historyItemReleaseTest by testSuite {
    for (kind in listOf(
        HistoryItemKind.Message,
        HistoryItemKind.PlanUpdate,
        HistoryItemKind.RequestUserInput,
        HistoryItemKind.SuggestSubagentTask,
    )) {
        test("evicted unvisited $kind releases its dormant loading job") {
            coroutineScope {
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                val itemOwner = supervisorChildScope()
                try {
                    val session = repository.open(repository.create())
                    val context = HistoryItemLoadContext(
                        AgentHistorySource(session.storage, session.runtime.latestIndex, session.runtime.state),
                        itemOwner, { true }, HistoryTurnDurationResolver(session.storage),
                    )
                    val descriptor = HistoryItemDescriptor(
                        1, HistoryItemSource.Index, kind, Duration.ZERO,
                    )
                    val item: HistoryItemViewModel = when (kind) {
                        HistoryItemKind.Message -> MessageHistoryItemViewModelImpl(1, descriptor, context)
                        HistoryItemKind.PlanUpdate -> PlanUpdateHistoryItemViewModelImpl(1, descriptor, context)
                        HistoryItemKind.RequestUserInput -> RequestUserInputHistoryItemViewModelImpl(1, descriptor, context)
                        HistoryItemKind.SuggestSubagentTask ->
                            SuggestSubagentTaskHistoryItemViewModelImpl(1, descriptor, context)
                        else -> error("Not a lazy leaf kind")
                    }
                    val ownerJob = requireNotNull(itemOwner.coroutineContext[Job])
                    val loading = ownerJob.children.single()
                    assertFalse(loading.isActive)
                    assertFalse(loading.isCompleted)

                    item.release()

                    assertTrue(loading.isCancelled)
                    loading.join()
                    assertEquals(0, ownerJob.children.count())
                    // A retained old row cannot revive a released load on re-entry.
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
