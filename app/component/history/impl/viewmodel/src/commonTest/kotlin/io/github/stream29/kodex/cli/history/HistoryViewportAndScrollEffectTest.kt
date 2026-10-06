package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryScrollEffect
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val historyViewportAndScrollEffectTest by testSuite {
    test("unmounted repeated exact targets persist and stale acknowledgments cannot consume them") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in listOf(3, 9, 20)) storage.index[index] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                model.requestScrollToStorageIndex(3)
                val first = model.awaitEffect()
                val firstTarget = assertIs<HistoryScrollTarget.Item>(first.target).item
                assertEquals(3, assertIs<MessageHistoryItemViewModel>(firstTarget).index)
                assertSame(firstTarget, model.historyItems.value.peek(0))
                assertTrue(model.historyItems.value.hasNewer)
                assertFalse(model.followsLatest.value)

                // No renderer/mount or acknowledgment has occurred.
                model.awaitNavigationReady()
                assertSame(first, model.pendingScrollEffect.value)
                model.requestScrollToStorageIndex(3)
                val second = model.awaitEffect()
                assertNotSame(first, second, "StateFlow must not deduplicate repeated destinations.")
                assertSame(firstTarget, assertIs<HistoryScrollTarget.Item>(second.target).item)
                model.acknowledgeScrollEffect(first)
                assertSame(second, model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(second)
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(first)
                assertNull(model.pendingScrollEffect.value)

                model.requestScrollToLatest()
                val latest = model.awaitEffect()
                assertEquals(HistoryScrollTarget.Latest, latest.target)
                assertEquals(20, assertIs<MessageHistoryItemViewModel>(model.historyItems.value.peek(0)).index)
                assertFalse(model.historyItems.value.hasNewer)
                assertTrue(model.followsLatest.value)
                model.requestScrollToLatest()
                val latestAgain = model.awaitEffect()
                assertNotSame(latest, latestAgain)
                model.acknowledgeScrollEffect(latest)
                assertSame(latestAgain, model.pendingScrollEffect.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("nonce invalidation and close withdraw pending destinations and reject old windows") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..8) storage.index[index] = navigationMessage("$index")
            }
            val nonce = MutableStateFlow(40L)
            val model = createAgentHistoryViewModel(
                AgentHistorySource(runtime.storage, runtime.latestIndex, runtime.state, nonce),
                supervisorChildScope(), MutableStateFlow(false),
            )
            try {
                model.awaitNavigationReady()
                model.requestScrollToStorageIndex(3)
                val effect = model.awaitEffect()
                val old = model.historyItems.value
                nonce.value = 41
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.historyItems.first { it.generation == 41L && it.size > 0 }
                    }
                }
                model.awaitNavigationReady()
                assertNull(model.pendingScrollEffect.value)
                model.setFollowsLatest(model.historyItems.value, false)
                model.setFollowsLatest(old, true)
                assertFalse(model.followsLatest.value)
                model.reportViewport(old, listOf(old.peek(0)))
                model.acknowledgeScrollEffect(effect)
                assertNull(model.pendingScrollEffect.value)
                assertFalse(model.contains(old.generation, 3))

                model.requestScrollToStorageIndex(3)
                val beforeClose = model.awaitEffect()
                val window = model.historyItems.value
                model.close()
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(beforeClose)
                model.requestScrollToStorageIndex(1)
                model.requestScrollToLatest()
                model.reportViewport(window, listOf(window.peek(0)))
                model.setFollowsLatest(window, true)
                window.requestOlder()
                window.requestNewer()
                assertNull(model.pendingScrollEffect.value)
                assertSame(window, model.historyItems.value)
                assertFalse(model.contains(window.generation, 3))
                assertFalse(model.followsLatest.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("revert withdraws the exact pending target without retargeting its old child") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..8) storage.index[index] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                model.requestScrollToStorageIndex(7)
                val effect = model.awaitEffect()
                runtime.modify { it.revert(4) }
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.historyItems.first { it.generation > effect.generation && it.size > 0 }
                    }
                }
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(effect)
                assertNull(model.pendingScrollEffect.value)
                assertEquals(3, assertIs<MessageHistoryItemViewModel>(model.historyItems.value.peek(0)).index)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("sparse paging protects the latest valid visible chunks and rejects stale or foreign reports") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..40) storage.index[index * 3] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                val stale = model.historyItems.value
                repeat(7) {
                    val window = model.historyItems.value
                    model.reportViewport(window, listOf(window.peek(0)))
                    model.page(window, newer = false)
                }
                val wide = model.historyItems.value
                assertEquals((120 downTo 99 step 3).toList(), wide.messageIndexes())
                val visible = wide.peek(6) // 102, not an ordinal storage cursor.
                val callerList = mutableListOf(visible)
                model.reportViewport(wide, callerList)
                callerList.clear() // Accepted reports must not borrow a mutable caller list.
                model.reportViewport(stale, listOf(stale.peek(0)))
                model.reportViewport(wide, listOf(
                    io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel(
                        999, kotlin.time.Duration.ZERO,
                    ),
                ))
                model.setFollowsLatest(wide, false)
                model.page(wide, newer = false)
                val older = model.historyItems.value
                assertEquals(listOf(105, 102, 99, 96), older.messageIndexes())
                assertTrue(older.hasNewer)
                assertSame(visible, older.peek(1))
                model.setFollowsLatest(older, true)
                assertFalse(model.followsLatest.value, "Newest viewport is not newest storage.")

                model.reportViewport(older, listOf(visible))
                model.page(older, newer = true)
                val newer = model.historyItems.value
                assertEquals(listOf(108, 105, 102, 99), newer.messageIndexes())
                assertSame(visible, newer.peek(2))
                assertTrue(newer.hasOlder)
                assertTrue(newer.hasNewer)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("visible Work Group identity protects its structural chunk rather than its child count") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.index[1] = navigationMessage("one")
                for (index in 2..20) storage.work[index] = StableTextToolEvent(
                    callId = "call-$index", name = "tool-$index",
                    arguments = JsonObject(emptyMap()), result = "done", success = true,
                )
                storage.index[21] = navigationMessage("seal")
                for (index in 22..30) storage.index[index] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                repeat(9) {
                    val window = model.historyItems.value
                    model.reportViewport(window, listOf(window.peek(0)))
                    model.page(window, newer = false)
                }
                val wide = model.historyItems.value
                val group = assertIs<WorkGroupHistoryItemViewModel>(wide.peek(wide.size - 1))
                assertEquals(19, group.itemCount)
                assertEquals(2..20, group.indexRange)
                model.reportViewport(wide, listOf(group))
                model.page(wide, newer = false)
                val bounded = model.historyItems.value
                assertEquals(4, bounded.size, "One neighboring chunk, group chunk and older chunk.")
                assertSame(group, bounded.peek(2))
                assertEquals(21, assertIs<MessageHistoryItemViewModel>(bounded.peek(1)).index)
                assertEquals(1, assertIs<MessageHistoryItemViewModel>(bounded.peek(3)).index)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun navigationMessage(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private fun HistoryItemWindow.messageIndexes(): List<Int> =
    List(size) { assertIs<MessageHistoryItemViewModel>(peek(it)).index }

private suspend fun AgentHistoryViewModel.awaitNavigationReady() = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) {
        val state = loadState.first { it == AgentHistoryLoadState.Ready || it is AgentHistoryLoadState.Failed }
        check(state == AgentHistoryLoadState.Ready) { "$state" }
    }
}

private suspend fun AgentHistoryViewModel.awaitEffect(): HistoryScrollEffect =
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { pendingScrollEffect.first { it != null }!! }
    }

private suspend fun AgentHistoryViewModel.page(window: HistoryItemWindow, newer: Boolean) {
    awaitNavigationReady()
    if (newer) window.requestNewer() else window.requestOlder()
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { historyItems.first { it !== window } }
    }
    awaitNavigationReady()
}
