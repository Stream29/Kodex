package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

val agentHistoryBoundedWindowTest by testSuite {
    test("long History navigation keeps only a viewport-derived local window") {
        coroutineScope {
            val itemCount = 1_000
            val viewportHeight = 12
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(itemCount) { position ->
                    storage.index[position + 1] = StableUserMessage(
                        content = listOf(ContentItem.InputText("$position")),
                    )
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            val started = TimeSource.Monotonic.markNow()
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        Column(modifier = Modifier.width(60).height(viewportHeight)) {
                            AgentHistoryView(
                                model = model,
                                shellSessions = BoundedWindowShellSessions,
                            )
                        }
                    }
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            model.loadState.first { state ->
                                state == AgentHistoryLoadState.Ready
                            }
                        }
                    }
                    settleHistory()
                    val initialLoadElapsed = started.elapsedNow()
                    val initialWindowSize = model.historyItems.value.size
                    assertTrue(initialWindowSize in 1 until itemCount)
                    assertTrue(
                        initialLoadElapsed < 2.seconds,
                        "Initial long-History viewport took $initialLoadElapsed.",
                    )

                    var peakWindowSize = initialWindowSize
                    repeat(200) {
                        val window = model.historyItems.value
                        if (!window.hasOlder) return@repeat
                        val newerMarkerCount = if (window.hasNewer) 1 else 0
                        model.listState.scrollToItem(newerMarkerCount + window.size)
                        settleHistory()
                        model.awaitWindowChange(window, HistoryItemWindow::requestOlder)
                        peakWindowSize = maxOf(peakWindowSize, model.historyItems.value.size)
                    }

                    assertTrue(model.historyItems.value.hasNewer)
                    repeat(200) {
                        val window = model.historyItems.value
                        if (!window.hasNewer) return@repeat
                        model.listState.scrollToItem(0)
                        settleHistory()
                        model.awaitWindowChange(window, HistoryItemWindow::requestNewer)
                        peakWindowSize = maxOf(peakWindowSize, model.historyItems.value.size)
                    }
                    assertFalse(model.historyItems.value.hasNewer)
                    assertTrue(
                        peakWindowSize <= viewportHeight * 4,
                        "A $viewportHeight-row History viewport retained " +
                            "$peakWindowSize of $itemCount items.",
                    )
                    val elapsed = started.elapsedNow()
                    assertTrue(
                        elapsed < 15.seconds,
                        "Bounded navigation took $elapsed.",
                    )
                    println(
                        "bounded History window: peak $peakWindowSize of $itemCount items " +
                            "in a $viewportHeight-row viewport, initial load in " +
                            "$initialLoadElapsed, round trip in $elapsed",
                    )
                }
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun AgentHistoryViewModel.awaitWindowChange(
    previous: HistoryItemWindow,
    request: (HistoryItemWindow) -> Unit,
) {
    if (historyItems.value === previous) request(previous)
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            historyItems.first { current -> current !== previous }
        }
    }
}

private suspend fun TestMosaic<String>.settleHistory() {
    repeat(8) {
        try {
            awaitSnapshot(100.milliseconds)
            yield()
        } catch (_: TimeoutCancellationException) {
            return
        }
    }
}

private object BoundedWindowShellSessions :
    io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry {
    override val activeSessions =
        kotlinx.coroutines.flow.MutableStateFlow<
            Map<Int, io.github.stream29.kodex.app.agent.contract.AgentShellSession>
            >(emptyMap())
}
