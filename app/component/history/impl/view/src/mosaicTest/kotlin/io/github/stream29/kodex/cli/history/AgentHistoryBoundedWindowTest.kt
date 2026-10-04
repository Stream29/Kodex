package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.testScope
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

val agentHistoryBoundedWindowTest by testSuite {
    test(
        "long History navigation keeps only a viewport-derived local window",
        testConfig = TestConfig.testScope(isEnabled = true, timeout = 360.seconds),
    ) {
        coroutineScope {
            val itemCount = 1_000
            val viewportHeight = 12
            // A round trip renders about 2,000 one-item steps. This is a
            // hang guard, not a platform-independent throughput benchmark.
            val navigationBudget = 300.seconds
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(itemCount) { position ->
                    storage.index[position + 1] = StableUserMessage(
                        content = listOf(ContentItem.InputText("$position")),
                    )
                }
            }
            val model = createAgentHistoryViewModel(
                AgentHistorySource(runtime.storage, runtime.latestIndex, runtime.state),
                supervisorChildScope(), kotlinx.coroutines.flow.MutableStateFlow(false),
            )
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
                        initialLoadElapsed < 5.seconds,
                        "Initial long-History viewport took $initialLoadElapsed.",
                    )

                    var peakWindowSize = initialWindowSize
                    // Programmatic positioning alone does not express leaving follow-latest.
                    model.scrollInteractionSource.tryEmit(
                        ScrollInteraction(
                            source = ScrollInputSource.Pointer,
                            orientation = ScrollOrientation.Vertical,
                            requestedDelta = -1,
                            consumedDelta = -1,
                        ),
                    )
                    assertFalse(model.followsLatest)
                    var olderSteps = 0
                    while (model.historyItems.value.hasOlder) {
                        assertTrue(
                            started.elapsedNow() < navigationBudget,
                            "Navigation did not reach the oldest item after $olderSteps steps; " +
                                "oldest=${model.historyItems.value.oldestMessageIndex}.",
                        )
                        val window = model.historyItems.value
                        val newerMarkerCount = if (window.hasNewer) 1 else 0
                        model.listState.scrollToItem(newerMarkerCount + window.size)
                        settleHistory()
                        awaitWindowProgress(model) { current ->
                            !current.hasOlder || current.oldestMessageIndex < window.oldestMessageIndex
                        }
                        peakWindowSize = maxOf(peakWindowSize, model.historyItems.value.size)
                        olderSteps++
                    }

                    assertFalse(model.historyItems.value.hasOlder)
                    assertTrue(model.historyItems.value.hasNewer)
                    var newerSteps = 0
                    while (model.historyItems.value.hasNewer) {
                        assertTrue(
                            started.elapsedNow() < navigationBudget,
                            "Navigation did not return to the latest item after $olderSteps older and " +
                                "$newerSteps newer steps; newest=${model.historyItems.value.newestMessageIndex}.",
                        )
                        val window = model.historyItems.value
                        model.listState.scrollToItem(0)
                        settleHistory()
                        awaitWindowProgress(model) { current ->
                            !current.hasNewer || current.newestMessageIndex > window.newestMessageIndex
                        }
                        peakWindowSize = maxOf(peakWindowSize, model.historyItems.value.size)
                        newerSteps++
                    }
                    assertFalse(model.historyItems.value.hasNewer)
                    assertTrue(
                        peakWindowSize <= viewportHeight * 4,
                        "A $viewportHeight-row History viewport retained " +
                            "$peakWindowSize of $itemCount items.",
                    )
                    val elapsed = started.elapsedNow()
                    assertTrue(
                        elapsed < navigationBudget,
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

private val HistoryItemWindow.oldestMessageIndex: Int
    get() = (peek(size - 1) as MessageHistoryItemViewModel).index

private val HistoryItemWindow.newestMessageIndex: Int
    get() = (peek(0) as MessageHistoryItemViewModel).index

private suspend fun TestMosaic<String>.awaitWindowProgress(
    model: AgentHistoryViewModel,
    predicate: (HistoryItemWindow) -> Boolean,
) = coroutineScope {
    // Edge demands are driven by rendered frames, not by direct ViewModel calls from the test.
    val frames = launch {
        while (true) settleHistory()
    }
    try {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                combine(
                    model.historyItems,
                    model.loadState,
                    snapshotFlow { model.listState.layoutInfo },
                ) { window, loadState, layout ->
                    // Publishing a window precedes the renderer's provider/anchor update.
                    // The next scroll must target the measured new provider, not yesterday's
                    // row indices. Keep exercising real viewport demand (never call requestOlder/
                    // requestNewer from this fixture to make a stalled renderer appear to pass).
                    val prefix = if (window.hasNewer) 1 else 0
                    val suffix = if (window.hasOlder) 1 else 0
                    val visibleRows = layout.visibleItemsInfo.filter {
                        it.key is MessageHistoryItemViewModel
                    }
                    predicate(window) &&
                        loadState == AgentHistoryLoadState.Ready &&
                        layout.totalItemsCount == prefix + window.size + suffix &&
                        visibleRows.isNotEmpty() &&
                        visibleRows.all { row ->
                            val position = row.index - prefix
                            position in 0 until window.size && window.peek(position) === row.key
                        }
                }.first { rendered -> rendered }
            }
        }
    } catch (failure: TimeoutCancellationException) {
        val window = model.historyItems.value
        val layout = model.listState.layoutInfo
        throw AssertionError(
            "History did not advance/render: newest=${window.newestMessageIndex}, " +
                "oldest=${window.oldestMessageIndex}, size=${window.size}, " +
                "older=${window.hasOlder}, newer=${window.hasNewer}, " +
                "load=${model.loadState.value}, count=${layout.totalItemsCount}, " +
                "visible=${layout.visibleItemsInfo.map { row ->
                    "${row.index}:${(row.key as? MessageHistoryItemViewModel)?.index ?: row.key}"
                }}",
            failure,
        )
    } finally {
        frames.cancel()
        frames.join()
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
