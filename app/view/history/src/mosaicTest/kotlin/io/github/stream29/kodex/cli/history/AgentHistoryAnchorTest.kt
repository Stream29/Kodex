package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val agentHistoryAnchorTest by testSuite {
    for ((newer, scrollOffset) in listOf(false to 0, false to 1, true to 0, true to 1)) {
        val direction = if (newer) "newer" else "older"
        test("keeps the anchor at offset $scrollOffset when the $direction window arrives late") {
            coroutineScope {
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                val runtime = repository.open(repository.create()).runtime
                runtime.modify { storage ->
                    repeat(20) { position ->
                        storage.index[position + 1] = StableUserMessage(
                            listOf(ContentItem.InputText("$position")),
                        )
                    }
                }
                val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
                try {
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            model.loadState.first { it == AgentHistoryLoadState.Ready }
                            model.requestScrollToStorageIndex(10)
                            model.historyItems.first { window ->
                                window.size == 1 &&
                                    (window.peek(0) as MessageHistoryItemViewModel).index == 10
                            }
                            repeat(7) {
                                model.loadState.first { it == AgentHistoryLoadState.Ready }
                                val window = model.historyItems.value
                                window.requestOlder()
                                model.historyItems.first { it !== window }
                            }
                            model.loadState.first { it == AgentHistoryLoadState.Ready }
                        }
                    }
                    val original = model.historyItems.value
                    assertEquals(8, original.size)
                    assertFalse(model.followsLatest)
                    var displayedWindow by mutableStateOf(original)
                    runMosaicTest {
                        // Hold the old provider across loading, without automatic edge demands.
                        setContentAndSnapshot {
                            val window = displayedWindow
                            LazyColumn(
                                modifier = Modifier.width(20).height(6),
                                state = model.listState,
                                reverseLayout = true,
                            ) {
                                if (window.hasNewer) item(key = "newer") {}
                                items(count = window.size, key = window::peek) { position ->
                                    val index = (window.peek(position) as MessageHistoryItemViewModel).index
                                    Text("$index\n$index")
                                }
                                if (window.hasOlder) item(key = "older") {}
                            }
                        }
                        model.listState.scrollToItem(index = 6, scrollOffset = scrollOffset)
                        settleAnchorFrames()
                        val anchor = model.listState.layoutInfo.visibleItemsInfo.first()
                        assertEquals(5, (anchor.key as MessageHistoryItemViewModel).index)
                        assertEquals(-scrollOffset, anchor.offset)

                        if (newer) original.requestNewer() else original.requestOlder()
                        val replacement = withContext(Dispatchers.Default) {
                            withTimeout(5.seconds) {
                                val window = model.historyItems.first { it !== original }
                                model.loadState.first { it == AgentHistoryLoadState.Ready }
                                window
                            }
                        }
                        settleAnchorFrames()
                        val oldProviderAnchor = model.listState.layoutInfo.visibleItemsInfo.first()
                        assertSame(anchor.key, oldProviderAnchor.key, "Loading must not reposition the old provider.")
                        assertEquals(anchor.offset, oldProviderAnchor.offset)

                        displayedWindow = replacement
                        settleAnchorFrames()
                        val newProviderAnchor = model.listState.layoutInfo.visibleItemsInfo.first()
                        assertSame(anchor.key, newProviderAnchor.key, "The new provider must restore the same key.")
                        assertEquals(anchor.offset, newProviderAnchor.offset)
                    }
                } finally {
                    model.close()
                    repository.cancelAndJoin()
                }
            }
        }
    }
}

private suspend fun TestMosaic<String>.settleAnchorFrames() {
    repeat(3) {
        try {
            awaitSnapshot(100.milliseconds)
        } catch (_: TimeoutCancellationException) {
            // awaitSnapshot still advances frames when no redraw is required.
        }
    }
}
