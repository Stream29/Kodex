package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val historyPagingReadinessTest by testSuite {
    for (newer in listOf(false, true)) {
        val direction = if (newer) "newer" else "older"
        test("accepts immediate $direction demand after publishing Ready") {
            coroutineScope {
                val itemCount = 20
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                val runtime = repository.open(repository.create()).runtime
                runtime.modify { storage ->
                    repeat(itemCount) { position ->
                        storage.index[position + 1] = StableUserMessage(
                            listOf(ContentItem.InputText("$position")),
                        )
                    }
                }
                val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
                try {
                    var requests = 0
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            model.loadState.first { it == AgentHistoryLoadState.Ready }
                            if (newer) {
                                model.requestScrollToStorageIndex(1)
                                model.historyItems.first { window ->
                                    window.size > 0 && window.hasNewer &&
                                        (window.peek(0) as MessageHistoryItemViewModel).index == 1
                                }
                                model.loadState.first { it == AgentHistoryLoadState.Ready }
                            }
                            // React inside the Ready publication, before the producer can continue.
                            withContext(Dispatchers.Unconfined) {
                                model.loadState.first { state ->
                                    check(state !is AgentHistoryLoadState.Failed) { "$state" }
                                    if (state != AgentHistoryLoadState.Ready) return@first false
                                    val window = model.historyItems.value
                                    val hasMore = if (newer) window.hasNewer else window.hasOlder
                                    if (hasMore) {
                                        requests++
                                        if (newer) window.requestNewer() else window.requestOlder()
                                    }
                                    !hasMore
                                }
                            }
                        }
                    }
                    assertTrue(requests >= 2, "The test must request consecutive pages.")
                    val window = model.historyItems.value
                    assertEquals(itemCount, (window.peek(0) as MessageHistoryItemViewModel).index)
                    if (newer) {
                        assertFalse(window.hasNewer)
                    } else {
                        assertFalse(window.hasOlder)
                        assertEquals(
                            (itemCount downTo 1).toList(),
                            List(window.size) { (window.peek(it) as MessageHistoryItemViewModel).index },
                        )
                    }
                } finally {
                    model.close()
                    repository.cancelAndJoin()
                }
            }
        }
    }
}
