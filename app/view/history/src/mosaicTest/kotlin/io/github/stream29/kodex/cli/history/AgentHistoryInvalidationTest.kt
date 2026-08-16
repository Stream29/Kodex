package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val agentHistoryInvalidationTest by testSuite {
    test("destructive replacement keeps an in-flight lazy provider on one window") {
        val oldWindow = TestHistoryItemWindow(
            generation = 0,
            items = List(70) { position ->
                HistoryItemViewModel.Message(index = 70 - position)
            },
        )
        val model = ReplaceableHistoryModel(oldWindow)

        runMosaicTest {
            setContentAndSnapshot {
                Column(modifier = Modifier.width(40).height(12)) {
                    AgentHistoryView(
                        model = model,
                        shellSessions = EmptyHistoryShellSessions,
                    )
                }
            }
            model.listState.scrollToItem(index = 54)
            awaitSnapshot()

            model.replace(
                TestHistoryItemWindow(
                    generation = 1,
                    items = emptyList(),
                ),
                AgentHistoryLoadState.Initializing,
            )
            assertTrue("Loading history…" in awaitSnapshot())

            val replacement = TestHistoryItemWindow(
                generation = 1,
                items = List(9) { position ->
                    HistoryItemViewModel.Message(index = 9 - position)
                },
            )
            model.replace(
                replacement,
                AgentHistoryLoadState.Ready(hasOlder = false),
            )
            awaitSnapshot()

            assertEquals(9, model.committedItems.value.size)
            assertEquals(9, model.committedItems.value.peek(0).storageIndex)
            repeat(oldWindow.size) { position ->
                oldWindow.peek(position)
            }
        }
    }
}

private class ReplaceableHistoryModel(
    initialWindow: HistoryItemWindow,
) : AgentHistoryViewModel {
    private val mutableCommittedItems = MutableStateFlow(initialWindow)
    private val mutableLoadState = MutableStateFlow<AgentHistoryLoadState>(
        AgentHistoryLoadState.Ready(hasOlder = false),
    )

    override val committedItems: StateFlow<HistoryItemWindow> = mutableCommittedItems
    override val loadState: StateFlow<AgentHistoryLoadState> = mutableLoadState
    override val pendingTools: StateFlow<List<UnstableCleanEvent>> = MutableStateFlow(emptyList())
    override val streamingItem: StateFlow<HistoryStreamingItem?> = MutableStateFlow(null)
    override val listState: LazyListState = LazyListState()
    override val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource()
    override val followsLatest: Boolean = false

    fun replace(
        window: HistoryItemWindow,
        loadState: AgentHistoryLoadState,
    ) {
        mutableCommittedItems.value = window
        mutableLoadState.value = loadState
    }

    override suspend fun read(item: HistoryItemViewModel): StableCleanEvent =
        StableCleanEvent.UserMessage(
            content = listOf(ContentItem.InputText(item.storageIndex.toString())),
        )

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
) : HistoryItemWindow {
    override val size: Int = items.size

    override fun peek(index: Int): HistoryItemViewModel = items[index]

    override fun get(index: Int): HistoryItemViewModel = items[index]
}

private object EmptyHistoryShellSessions : AgentShellSessionRegistry {
    override val activeSessions: StateFlow<Map<Int, AgentShellSession>> =
        MutableStateFlow(emptyMap())
}

private val HistoryItemViewModel.storageIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.Message -> index
        is HistoryItemViewModel.Reasoning -> index
        is HistoryItemViewModel.Tool -> index
        is HistoryItemViewModel.Patch -> index
        is HistoryItemViewModel.PlanUpdate -> index
        is HistoryItemViewModel.ContextCompaction -> index
    }
