package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val historyPagingContractTest by testSuite {
    test("peek does not load; access prefetches one page and becomes idle before the edge") {
        withPagingModel { model ->
            val initial = model.historyItems.value
            assertEquals(HistoryPaging.InitialLoadSize, initial.size)
            repeat(initial.size) { initial.peek(it) }
            delay(100)
            assertSame(initial, model.historyItems.value)

            val position = initial.size - HistoryPaging.PrefetchDistance / 2
            val accessed = initial[position]
            assertTrue(position < initial.size - 1, "Demand must occur before the last item.")
            val loaded = model.awaitPageAfter(initial)
            assertEquals(HistoryPaging.InitialLoadSize + HistoryPaging.PageSize, loaded.size)
            assertSame(accessed, loaded.peek(position))
            delay(100)
            assertSame(loaded, model.historyItems.value, "No repeated idle load/drop cycle.")

            // A replaced snapshot cannot inject a fresh access or an explicit paging demand.
            initial[initial.size - 1]
            initial.requestOlder()
            initial.requestNewer()
            delay(100)
            assertSame(loaded, model.historyItems.value)
        }
    }

    test("bidirectional prefetch traverses sparse indexes with bounded memory and exact anchors") {
        withPagingModel { model ->
            model.setFollowsLatest(model.historyItems.value, false)
            var page = model.historyItems.value
            var peak = page.size
            while (page.hasOlder) {
                val anchor = page[page.size - HistoryPaging.PrefetchDistance / 2]
                model.reportViewport(page, listOf(anchor))
                val next = model.awaitPageAfter(page)
                assertTrue((0 until next.size).any { next.peek(it) === anchor })
                assertContiguousSparseIndexes(next)
                peak = maxOf(peak, next.size)
                page = next
            }
            assertEquals(3, (page.peek(page.size - 1) as MessageHistoryItemViewModel).index)
            assertTrue(page.hasNewer)
            while (page.hasNewer) {
                val anchor = page[HistoryPaging.PrefetchDistance / 2]
                model.reportViewport(page, listOf(anchor))
                val next = model.awaitPageAfter(page)
                assertTrue((0 until next.size).any { next.peek(it) === anchor })
                assertContiguousSparseIndexes(next)
                peak = maxOf(peak, next.size)
                page = next
            }
            assertEquals(3_000, (page.peek(0) as MessageHistoryItemViewModel).index)
            assertTrue(peak <= HistoryPaging.MaxSize, "Peak retained $peak top-level items.")
            assertFalse(model.followsLatest.value, "Paging alone cannot restore follow intent.")
            delay(100)
            assertSame(page, model.historyItems.value)
        }
    }
}

private suspend fun withPagingModel(block: suspend (AgentHistoryViewModel) -> Unit) = coroutineScope {
    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
    val runtime = repository.open(repository.create()).runtime
    runtime.modify { storage ->
        for (index in 1..1_000) {
            storage.index[index * 3] = StableUserMessage(listOf(ContentItem.InputText("$index")))
        }
    }
    val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
    try {
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                model.loadState.first { it == AgentHistoryLoadState.Ready }
                block(model)
            }
        }
    } finally {
        model.close()
        repository.cancelAndJoin()
    }
}

private suspend fun AgentHistoryViewModel.awaitPageAfter(previous: HistoryItemWindow): HistoryItemWindow {
    historyItems.first { it !== previous }
    val state = loadState.first { it == AgentHistoryLoadState.Ready || it is AgentHistoryLoadState.Failed }
    check(state == AgentHistoryLoadState.Ready) { "$state" }
    return historyItems.value
}

private fun assertContiguousSparseIndexes(window: HistoryItemWindow) {
    val indexes = List(window.size) { (window.peek(it) as MessageHistoryItemViewModel).index }
    assertTrue(indexes.zipWithNext().all { (newer, older) -> newer - older == 3 })
}
