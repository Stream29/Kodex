package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
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
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.cli.components.LazyListLayoutInfo
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
            val modelOwner = supervisorChildScope()
            var ownedModel: AgentHistoryViewModel? = null
            try {
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
                    modelOwner, kotlinx.coroutines.flow.MutableStateFlow(false),
                ).also { ownedModel = it }
                val viewState = AgentHistoryViewState()
                val started = TimeSource.Monotonic.markNow()
                runMosaicTest {
                    try {
                        setContentAndSnapshot {
                            Column(modifier = Modifier.width(60).height(viewportHeight)) {
                                AgentHistoryView(
                                    model = model,
                                    viewState = viewState,
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
                        // Default-dispatcher payloads can finish after the first blank-row frame.
                        // A wheel attempt with no loaded scrollable height correctly consumes zero.
                        val initialWindow = awaitWindowProgress(
                            model, viewState, requireScrollable = true,
                        ) { it.size > 0 }
                        val initialLoadElapsed = started.elapsedNow()
                        val initialWindowSize = initialWindow.size
                        assertTrue(initialWindowSize in 1 until itemCount)
                        assertTrue(
                            initialLoadElapsed < 5.seconds,
                            "Initial long-History viewport took $initialLoadElapsed.",
                        )

                        var peakWindowSize = initialWindowSize
                        // Programmatic positioning alone does not express leaving follow-latest.
                        sendMouseEvent(
                            MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp),
                        )
                        val firstScrolledOutput = awaitSnapshot()
                        val scrolledOutput = settleHistory() ?: firstScrolledOutput
                        assertTrue("999" !in scrolledOutput, "Real pointer scrolling must leave the newest message.")
                        assertTrue("User" in scrolledOutput, "The scrolled viewport must render stored messages.")
                        assertFalse(model.followsLatest.value)
                        var renderedWindow = awaitWindowProgress(model, viewState) { it.size > 0 }
                        var olderSteps = 0
                        while (renderedWindow.hasOlder) {
                            assertTrue(
                                started.elapsedNow() < navigationBudget,
                                "Navigation did not reach the oldest item after $olderSteps steps; " +
                                    "oldest=${model.historyItems.value.oldestMessageIndex}.",
                            )
                            val window = renderedWindow
                            // A consumed logical edge request is not retained across publication or
                            // late payload height. Exercise continuous real input, as a user would,
                            // instead of expecting one programmatic request to pin every next edge.
                            renderedWindow = awaitWindowProgress(
                                model, viewState, navigationInput = MouseEvent.Button.WheelUp,
                                inputDescription = "older step=$olderSteps " +
                                    "captured=${window.newestMessageIndex}..${window.oldestMessageIndex}",
                            ) { current ->
                                !current.hasOlder || current.oldestMessageIndex < window.oldestMessageIndex
                            }
                            peakWindowSize = maxOf(peakWindowSize, renderedWindow.size)
                            olderSteps++
                        }

                        assertFalse(renderedWindow.hasOlder)
                        assertTrue(renderedWindow.hasNewer)
                        var newerSteps = 0
                        while (renderedWindow.hasNewer) {
                            assertTrue(
                                started.elapsedNow() < navigationBudget,
                                "Navigation did not return to the latest item after $olderSteps older and " +
                                    "$newerSteps newer steps; newest=${model.historyItems.value.newestMessageIndex}.",
                            )
                            val window = renderedWindow
                            renderedWindow = awaitWindowProgress(
                                model, viewState, navigationInput = MouseEvent.Button.WheelDown,
                                inputDescription = "newer step=$newerSteps " +
                                    "captured=${window.newestMessageIndex}..${window.oldestMessageIndex}",
                            ) { current ->
                                !current.hasNewer || current.newestMessageIndex > window.newestMessageIndex
                            }
                            peakWindowSize = maxOf(peakWindowSize, renderedWindow.size)
                            newerSteps++
                        }
                        assertFalse(renderedWindow.hasNewer)
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
                    } finally {
                        // Mosaic's test helper does not cancel its renderer when the block throws.
                        cancel()
                    }
                }
            } finally {
                try {
                    ownedModel?.close()
                } finally {
                    withContext(NonCancellable) {
                        try {
                            modelOwner.cancelAndJoin()
                        } finally {
                            repository.cancelAndJoin()
                        }
                    }
                }
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
    viewState: AgentHistoryViewState,
    requireScrollable: Boolean = false,
    navigationInput: MouseEvent.Button? = null,
    inputDescription: String = "await measured window",
    predicate: (HistoryItemWindow) -> Boolean,
) = coroutineScope {
    // Keep observations, not another owner/window authority. In particular peek never starts
    // offscreen payload jobs just to make failure diagnostics (or this fixture) look ready.
    val observations = ArrayDeque<String>()
    var lastInput = "none (zero-consumption attempts emit no interaction)"
    fun observe(label: String) {
        val observation = "$label; lastInteraction=$lastInput: ${model.historyDiagnostics(viewState)}"
        if (observations.lastOrNull() != observation) {
            if (observations.size == 16) observations.removeFirst()
            observations.addLast(observation)
        }
    }
    val inputs = launch(start = CoroutineStart.UNDISPATCHED) {
        viewState.scrollInteractionSource.interactions.collect { interaction ->
            lastInput = "${interaction.source}/${interaction.orientation} " +
                "requested=${interaction.requestedDelta}, consumed=${interaction.consumedDelta}"
        }
    }
    observe("$inputDescription before frames; input=$navigationInput")
    // Edge demands are driven by rendered frames, not by direct ViewModel calls from the test.
    val frames = launch {
        while (true) {
            navigationInput?.let { button ->
                sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, button))
            }
            settleHistory()
            observe("$inputDescription after frames; input=$navigationInput")
        }
    }
    try {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                combine(
                    model.historyItems,
                    model.loadState,
                    snapshotFlow {
                        viewState.listState.layoutInfo to viewState.listState.canScrollBackward
                    },
                ) { window, loadState, viewport ->
                    val (layout, canScrollBackward) = viewport
                    // Publishing a window precedes the renderer's provider/anchor update.
                    // The next scroll must target the measured new provider, not yesterday's
                    // row indices. Keep exercising real viewport demand (never call requestOlder/
                    // requestNewer from this fixture to make a stalled renderer appear to pass).
                    window.takeIf {
                        predicate(window) &&
                            loadState == AgentHistoryLoadState.Ready &&
                            window.isMeasuredHistoryWindow(
                                model, layout, canScrollBackward, requireScrollable,
                                requireReadyRows = true,
                            )
                    }
                }.first { rendered -> rendered != null }!!
            }
        }
    } catch (failure: TimeoutCancellationException) {
        observe("timeout")
        throw AssertionError(
            "History did not advance/render:\n${observations.joinToString("\n")}",
            failure,
        )
    } finally {
        frames.cancel()
        inputs.cancel()
        withContext(NonCancellable) {
            frames.join()
            inputs.join()
        }
    }
}

private fun AgentHistoryViewModel.historyDiagnostics(viewState: AgentHistoryViewState): String {
    val window = historyItems.value
    val list = viewState.listState
    val layout = list.layoutInfo
    fun MessageHistoryItemViewModel.payloadDescription(): String = when (val payload = state.value) {
        is MessageHistoryItemState.Loading ->
            "Loading(active=${payload.loadingJob.isActive}, completed=${payload.loadingJob.isCompleted}, " +
                "cancelled=${payload.loadingJob.isCancelled})"
        is MessageHistoryItemState.Ready -> "Ready"
        MessageHistoryItemState.Failed -> "Failed"
    }
    val children = (0 until window.size).map { position ->
        val child = window.peek(position) as MessageHistoryItemViewModel
        "$position:${child.index}=${child.payloadDescription()}"
    }
    val visible = layout.visibleItemsInfo.map { row ->
        val child = row.key as? MessageHistoryItemViewModel
        "${row.index}:${child?.index ?: row.key}@${row.offset}+${row.size}" +
            (child?.let { "=${it.payloadDescription()}" } ?: "")
    }
    val effect = pendingScrollEffect.value
    return "newest=${if (window.size > 0) window.newestMessageIndex else null}, " +
        "oldest=${if (window.size > 0) window.oldestMessageIndex else null}, " +
        "generation=${window.generation}, size=${window.size}, older=${window.hasOlder}, " +
        "newer=${window.hasNewer}, load=${loadState.value}, count=${layout.totalItemsCount}, " +
        "viewport=${layout.viewportStartOffset}..${layout.viewportEndOffset}, " +
        "anchor=${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}, " +
        "canScroll=${list.canScrollBackward}/${list.canScrollForward}, follows=${followsLatest.value}, " +
        "pendingEffect=${effect?.generation}:${effect?.target}, windowStates=$children, visible=$visible; " +
        "private list request / VM pending demand admission unavailable"
}

// Test-only readiness for the message-only bounded-navigation fixture, not a renderer authority.
internal fun HistoryItemWindow.isMeasuredHistoryWindow(
    model: AgentHistoryViewModel,
    layout: LazyListLayoutInfo,
    canScrollBackward: Boolean,
    requireScrollable: Boolean = false,
    inputTarget: MessageHistoryItemViewModel? = null,
    expectedTargetHeight: Int? = null,
    requireReadyRows: Boolean = false,
): Boolean {
    val prefix = if (hasNewer) 1 else 0
    val suffix = if (hasOlder) 1 else 0
    val visibleRows = layout.visibleItemsInfo.filter { it.key is MessageHistoryItemViewModel }
    return model.historyItems.value === this &&
        model.loadState.value == AgentHistoryLoadState.Ready &&
        layout.totalItemsCount == prefix + size + suffix &&
        visibleRows.isNotEmpty() &&
        (!requireScrollable || (canScrollBackward && visibleRows.all { row ->
            (row.key as MessageHistoryItemViewModel).state.value is MessageHistoryItemState.Ready
        })) &&
        // Both message-only fixtures use single-line User payloads: header + content = 2 rows.
        // Unlike an initial-only wait, every navigation handoff must reject an unloaded visible
        // row and Ready that still borrows its previously measured one-line Loading geometry.
        (!requireReadyRows || visibleRows.all { row ->
            (row.key as MessageHistoryItemViewModel).state.value is MessageHistoryItemState.Ready &&
                row.size == 2
        }) &&
        visibleRows.all { row ->
            val position = row.index - prefix
            position in 0 until size && peek(position) === row.key
        } &&
        // Optional barrier for controlled one-line-message gates: Ready can precede the frame
        // collecting it, and an offscreen payload can be Ready without being input-visible.
        (inputTarget == null || (
            inputTarget.state.value is MessageHistoryItemState.Ready &&
                visibleRows.any { row ->
                    row.key === inputTarget && row.size > 0 &&
                        (expectedTargetHeight == null || row.size == expectedTargetHeight) &&
                        row.offset < layout.viewportEndOffset &&
                        row.offset + row.size > layout.viewportStartOffset
                }
        )) &&
        // Default-dispatcher publication can race even this short readiness check.
        model.historyItems.value === this &&
        model.loadState.value == AgentHistoryLoadState.Ready
}

private suspend fun TestMosaic<String>.settleHistory(): String? {
    var lastOutput: String? = null
    repeat(8) {
        try {
            lastOutput = awaitSnapshot(100.milliseconds)
            yield()
        } catch (_: TimeoutCancellationException) {
            return lastOutput
        }
    }
    return lastOutput
}

private object BoundedWindowShellSessions :
    io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry {
    override val activeSessions =
        kotlinx.coroutines.flow.MutableStateFlow<
            Map<Int, io.github.stream29.kodex.app.agent.contract.AgentShellSession>
            >(emptyMap())
}
