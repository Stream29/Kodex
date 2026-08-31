package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

val agentHistoryInvalidationTest by testSuite {
    test("LazyColumn requests older History only when its demand marker is measured") {
        var olderRequests = 0
        val window = TestHistoryItemWindow(
            generation = 0,
            items = List(70) { position ->
                ReasoningHistoryItemViewModel(index = 70 - position, elapsed = Duration.ZERO)
            },
            hasOlder = true,
            onRequestOlder = { olderRequests += 1 },
        )
        val model = ReplaceableHistoryModel(window)
        model.replace(window, AgentHistoryLoadState.Ready)

        runMosaicTest {
            setContentAndSnapshot {
                Column(modifier = Modifier.width(40).height(12)) {
                    AgentHistoryView(
                        model = model,
                        shellSessions = EmptyInvalidationHistoryShellSessions,
                    )
                }
            }
            assertEquals(0, olderRequests)

            model.listState.scrollToItem(index = 70)
            awaitSnapshot()
            yield()
            assertEquals(1, olderRequests)
        }
    }

    test("LazyColumn requests newer History only when its demand marker is measured") {
        var newerRequests = 0
        val window = TestHistoryItemWindow(
            generation = 0,
            items = List(70) { position ->
                ReasoningHistoryItemViewModel(index = 70 - position, elapsed = Duration.ZERO)
            },
            hasNewer = true,
            onRequestNewer = { newerRequests += 1 },
        )
        val model = ReplaceableHistoryModel(window)
        model.listState.scrollToItem(index = 60)

        runMosaicTest {
            setContentAndSnapshot {
                Column(modifier = Modifier.width(40).height(12)) {
                    AgentHistoryView(
                        model = model,
                        shellSessions = EmptyInvalidationHistoryShellSessions,
                    )
                }
            }
            assertEquals(0, newerRequests)

            model.listState.scrollToItem(index = 0)
            awaitSnapshot()
            yield()
            assertEquals(1, newerRequests)
        }
    }

    test("destructive replacement keeps the old window readable") {
        val oldWindow = TestHistoryItemWindow(
            generation = 0,
            items = List(70) { position ->
                ReasoningHistoryItemViewModel(index = 70 - position, elapsed = Duration.ZERO)
            },
        )
        val model = ReplaceableHistoryModel(oldWindow)

        runMosaicTest {
            setContentAndSnapshot {
                Column(modifier = Modifier.width(40).height(12)) {
                    AgentHistoryView(
                        model = model,
                        shellSessions = EmptyInvalidationHistoryShellSessions,
                    )
                }
            }
            model.listState.scrollToItem(index = 54)
            awaitSnapshot()

            model.replace(
                TestHistoryItemWindow(generation = 1, items = emptyList()),
                AgentHistoryLoadState.Initializing,
            )
            assertTrue("Loading history…" in awaitSnapshot())

            val replacement = TestHistoryItemWindow(
                generation = 1,
                items = List(9) { position ->
                    ReasoningHistoryItemViewModel(index = 9 - position, elapsed = Duration.ZERO)
                },
            )
            model.replace(replacement, AgentHistoryLoadState.Ready)
            awaitSnapshot()

            assertEquals(9, model.historyItems.value.size)
            assertEquals(9, model.historyItems.value.peek(0).storageIndex)
            repeat(oldWindow.size) { position -> oldWindow.peek(position) }
        }
    }
}

private class ReplaceableHistoryModel(
    initialWindow: HistoryItemWindow,
) : AgentHistoryViewModel {
    private val mutableCommittedItems = MutableStateFlow(initialWindow)
    private val mutableLoadState = MutableStateFlow<AgentHistoryLoadState>(
        AgentHistoryLoadState.Ready,
    )

    override val historyItems: StateFlow<HistoryItemWindow> = mutableCommittedItems
    override val loadState: StateFlow<AgentHistoryLoadState> = mutableLoadState
    override val pendingTools: StateFlow<List<UnstableCleanEvent>> = MutableStateFlow(emptyList())
    override val streamingItem: StateFlow<HistoryStreamingItem?> = MutableStateFlow(null)
    override val activeTurnDuration: StateFlow<Duration?> = MutableStateFlow(null)
    override val listState: LazyListState = LazyListState()
    override val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource()
    override val followsLatest: Boolean = false

    fun replace(window: HistoryItemWindow, loadState: AgentHistoryLoadState) {
        mutableCommittedItems.value = window
        mutableLoadState.value = loadState
    }

    override fun contains(generation: Long, storageIndex: Int): Boolean {
        val window = mutableCommittedItems.value
        return generation == window.generation &&
            (0 until window.size).any { position ->
                window.peek(position).storageIndex == storageIndex
            }
    }

    override fun notifyContentChanged() = Unit

    override fun requestScrollToLatest() = Unit

    override fun close() = Unit
}

private class TestHistoryItemWindow(
    override val generation: Long,
    private val items: List<HistoryItemViewModel>,
    override val hasOlder: Boolean = false,
    override val hasNewer: Boolean = false,
    private val onRequestOlder: () -> Unit = {},
    private val onRequestNewer: () -> Unit = {},
) : HistoryItemWindow {
    override val size: Int = items.size

    override fun peek(index: Int): HistoryItemViewModel = items[index]

    override fun get(index: Int): HistoryItemViewModel = items[index]

    override fun requestOlder() {
        onRequestOlder()
    }

    override fun requestNewer() {
        onRequestNewer()
    }
}

private object EmptyInvalidationHistoryShellSessions : AgentShellSessionRegistry {
    override val activeSessions: StateFlow<Map<Int, AgentShellSession>> =
        MutableStateFlow(emptyMap())
}

private val HistoryItemViewModel.storageIndex: Int
    get() = when (this) {
        is ReasoningHistoryItemViewModel -> index
        else -> error("This test only uses reasoning history items.")
    }
