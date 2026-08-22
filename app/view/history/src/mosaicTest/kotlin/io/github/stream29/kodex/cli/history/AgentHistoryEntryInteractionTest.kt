package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryTurnFooterState
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val ansiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val agentHistoryEntryInteractionTest by testSuite {
    test("the complete multiline entry has no focus highlight and supports secondary actions") {
        var callbackCount by mutableStateOf(0)
        var capturedGeneration: Long? = null
        var capturedIndex: Int? = null
        var capturedAnchorPlaced = false
        var capturedClickPosition: IntOffset? = IntOffset(x = -1, y = -1)
        val item = HistoryItemViewModel.Message(index = 17)
        val model = SingleItemHistoryModel(
            item = item,
            readEvent = {
                StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("first line\nsecond line")),
                )
            },
        )

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            var initial = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 4,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { generation, storageIndex, anchor, clickPosition ->
                            capturedGeneration = generation
                            capturedIndex = storageIndex
                            capturedAnchorPlaced = anchor.isPlaced
                            capturedClickPosition = clickPosition
                            callbackCount++
                        },
                    )
                    Text(
                        value = "callbacks=$callbackCount",
                        modifier = Modifier.focusable(autoFocus = true),
                    )
                }
            }
            if ("first line" !in initial) initial = awaitSnapshot()

            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Release))
            assertEquals(initial, awaitSnapshot())

            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(1, callbackCount)
            assertEquals(4, capturedGeneration)
            assertEquals(17, capturedIndex)
            assertTrue(capturedAnchorPlaced)
            assertEquals(IntOffset(x = 6, y = 2), capturedClickPosition)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.F10,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            awaitSnapshot()

            assertEquals(2, callbackCount)
            assertEquals(null, capturedClickPosition)
        }
    }

    test("a nested tool control remains expandable and keeps the row secondary action") {
        var callbackCount by mutableStateOf(0)
        val item = HistoryItemViewModel.Tool(index = 23)
        val model = SingleItemHistoryModel(
            item = item,
            readEvent = {
                StableTextToolEvent(
                    callId = "call",
                    name = "demo",
                    arguments = JsonObject(emptyMap()),
                    result = "done",
                    success = true,
                )
            },
        )

        runMosaicTest {
            var collapsed = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 8,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, _, _, _ -> callbackCount++ },
                    )
                    Text("callbacks=$callbackCount")
                }
            }
            if ("> demo" !in collapsed) collapsed = awaitSnapshot()
            assertTrue("> demo" in collapsed)

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Release))
            val expanded = awaitSnapshot()
            assertTrue("v demo" in expanded)
            assertTrue(item.expanded)

            sendMouseEvent(MouseEvent(0, 2, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(0, 2, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(1, callbackCount)
        }
    }

    test("a work group expands into exact reusable child entries") {
        var callbackCount by mutableStateOf(0)
        var capturedIndex: Int? = null
        val newer = HistoryItemViewModel.Tool(index = 29)
        val older = HistoryItemViewModel.Tool(index = 23)
        val group = HistoryItemViewModel.WorkGroup(listOf(newer, older))
        val model = WorkGroupHistoryModel(
            group = group,
            events = mapOf(
                newer to textToolEvent(name = "newer"),
                older to textToolEvent(name = "older"),
            ),
            elapsed = mapOf(
                group to 5.seconds,
                newer to 3.seconds,
                older to 2.seconds,
            ),
        )

        runMosaicTest {
            var collapsed = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryWorkGroup(
                        group = group,
                        generation = 12,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, storageIndex, _, _ ->
                            capturedIndex = storageIndex
                            callbackCount++
                        },
                    )
                    Text("callbacks=$callbackCount")
                }
            }
            while ("> Take 2 actions · +5s" !in collapsed) {
                collapsed = awaitSnapshot()
            }

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Release))
            assertEquals(0, callbackCount)

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Release))
            var expanded = awaitSnapshot()
            while (
                "> newer · +3s" !in expanded ||
                "> older · +2s" !in expanded
            ) {
                expanded = awaitSnapshot()
            }
            assertTrue("v Take 2 actions · +5s" in expanded)
            assertTrue(group.expanded)

            sendMouseEvent(MouseEvent(0, 1, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(0, 1, MouseEvent.Type.Release))
            awaitSnapshot()
            assertEquals(1, callbackCount)
            assertEquals(29, capturedIndex)

            sendMouseEvent(MouseEvent(0, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(0, 1, MouseEvent.Type.Release))
            var childExpanded = awaitSnapshot()
            while ("v newer" !in childExpanded) {
                childExpanded = awaitSnapshot()
            }
            assertTrue("v newer" in childExpanded)
            assertTrue(newer.expanded)
        }
    }

    test("a committed entry renders elapsed returned by its model") {
        val item = HistoryItemViewModel.Message(index = 30)
        val model = SingleItemHistoryModel(
            item = item,
            readEvent = {
                StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("loaded")),
                )
            },
            elapsed = 1_500.milliseconds,
        )

        runMosaicTest {
            var rendered = setContentAndSnapshot {
                Column(modifier = Modifier.width(24)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 1,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, _, _, _ -> },
                    )
                }
            }
            while ("loaded" !in rendered) rendered = awaitSnapshot()

            assertEquals("Assistant · +1.5s\nloaded", rendered)
        }
    }

    test("an elapsed metadata failure does not replace committed content with Error") {
        val item = HistoryItemViewModel.Message(index = 31)
        val model = SingleItemHistoryModel(
            item = item,
            readEvent = {
                StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("loaded")),
                )
            },
            elapsedFailure = IllegalStateException("broken timestamp"),
        )

        runMosaicTest {
            var rendered = setContentAndSnapshot {
                Column(modifier = Modifier.width(24)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 1,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, _, _, _ -> },
                    )
                }
            }
            while ("loaded" !in rendered) rendered = awaitSnapshot()

            assertEquals("Assistant\nloaded", rendered)
            assertTrue("Error" !in rendered)
        }
    }

    test("a loading committed entry reserves exactly one blank row") {
        val item = HistoryItemViewModel.Message(index = 31)
        val event = CompletableDeferred<StableCleanEvent>()
        val model = SingleItemHistoryModel(
            item = item,
            readEvent = { event.await() },
        )

        runMosaicTest {
            val loading = setContentAndSnapshot {
                Column(modifier = Modifier.width(20)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 1,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, _, _, _ -> },
                    )
                    Text("after")
                }
            }

            assertEquals("\nafter", loading)

            event.complete(
                StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("loaded")),
                ),
            )
            assertEquals("Assistant\nloaded\nafter", awaitSnapshot())
        }
    }

    test("a failed committed entry renders a red Error row") {
        val item = HistoryItemViewModel.Message(index = 37)
        val model = SingleItemHistoryModel(
            item = item,
            readEvent = { error("broken history") },
        )

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            var failed = setContentAndSnapshot {
                Column(modifier = Modifier.width(20)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 1,
                        model = model,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, _, _, _ -> },
                    )
                }
            }
            if ("Error" !in failed) failed = awaitSnapshot()

            assertTrue("Error" in failed)
            assertTrue("\u001B[38;2;255;0;0m" in failed)
        }
    }
}

private object EmptyAgentShellSessionRegistry : AgentShellSessionRegistry {
    override val activeSessions =
        MutableStateFlow<Map<Int, AgentShellSession>>(emptyMap())
}

private class SingleItemHistoryModel(
    private val item: HistoryItemViewModel,
    private val readEvent: suspend () -> StableCleanEvent,
    private val elapsed: Duration? = null,
    private val elapsedFailure: Throwable? = null,
) : AgentHistoryViewModel {
    override val committedItems: StateFlow<HistoryItemWindow> =
        MutableStateFlow(SingleItemWindow(item))
    override val loadState: StateFlow<AgentHistoryLoadState> =
        MutableStateFlow(AgentHistoryLoadState.Ready(hasOlder = false))
    override val pendingTools: StateFlow<List<UnstableCleanEvent>> = MutableStateFlow(emptyList())
    override val streamingItem: StateFlow<HistoryStreamingItem?> = MutableStateFlow(null)
    override val historyTurnFooter: StateFlow<HistoryTurnFooterState?> = MutableStateFlow(null)
    override val activeTurnDuration: StateFlow<Duration?> = MutableStateFlow(null)
    override val listState: LazyListState = LazyListState()
    override val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource()
    override val followsLatest: Boolean = true

    override suspend fun read(item: HistoryItemViewModel): StableCleanEvent {
        require(item === this.item)
        return readEvent()
    }

    override suspend fun elapsedSincePrevious(item: HistoryItemViewModel): Duration? {
        require(item === this.item)
        elapsedFailure?.let { failure -> throw failure }
        return elapsed
    }

    override fun contains(generation: Long, storageIndex: Int): Boolean =
        storageIndex == item.storageIndex

    override fun notifyContentChanged() = Unit

    override fun requestScrollToLatest() = Unit

    override fun close() = Unit
}

private class WorkGroupHistoryModel(
    group: HistoryItemViewModel.WorkGroup,
    private val events: Map<HistoryItemViewModel, StableCleanEvent>,
    private val elapsed: Map<HistoryItemViewModel, Duration?> = emptyMap(),
) : AgentHistoryViewModel {
    override val committedItems: StateFlow<HistoryItemWindow> =
        MutableStateFlow(SingleItemWindow(group))
    override val loadState: StateFlow<AgentHistoryLoadState> =
        MutableStateFlow(AgentHistoryLoadState.Ready(hasOlder = false))
    override val pendingTools: StateFlow<List<UnstableCleanEvent>> = MutableStateFlow(emptyList())
    override val streamingItem: StateFlow<HistoryStreamingItem?> = MutableStateFlow(null)
    override val historyTurnFooter: StateFlow<HistoryTurnFooterState?> = MutableStateFlow(null)
    override val activeTurnDuration: StateFlow<Duration?> = MutableStateFlow(null)
    override val listState: LazyListState = LazyListState()
    override val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource()
    override val followsLatest: Boolean = true

    override suspend fun read(item: HistoryItemViewModel): StableCleanEvent = events.getValue(item)

    override suspend fun elapsedSincePrevious(item: HistoryItemViewModel): Duration? = elapsed[item]

    override fun contains(generation: Long, storageIndex: Int): Boolean =
        events.keys.any { item -> item.storageIndex == storageIndex }

    override fun notifyContentChanged() = Unit

    override fun requestScrollToLatest() = Unit

    override fun close() = Unit
}

private class SingleItemWindow(
    private val item: HistoryItemViewModel,
) : HistoryItemWindow {
    override val generation: Long = 0
    override val size: Int = 1

    override fun peek(index: Int): HistoryItemViewModel {
        require(index == 0)
        return item
    }

    override fun get(index: Int): HistoryItemViewModel = peek(index)
}

private val HistoryItemViewModel.storageIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.Message -> index
        is HistoryItemViewModel.Reasoning -> index
        is HistoryItemViewModel.Tool -> index
        is HistoryItemViewModel.RequestUserInput -> index
        is HistoryItemViewModel.Patch -> index
        is HistoryItemViewModel.PlanUpdate -> index
        is HistoryItemViewModel.ContextCompaction -> index
        is HistoryItemViewModel.WorkGroup -> indexRange.last
        is HistoryItemViewModel.TurnFooter -> error("A turn footer has no storage index.")
    }

private fun textToolEvent(name: String): StableTextToolEvent =
    StableTextToolEvent(
        callId = "call-$name",
        name = name,
        arguments = JsonObject(emptyMap()),
        result = "done",
        success = true,
    )
