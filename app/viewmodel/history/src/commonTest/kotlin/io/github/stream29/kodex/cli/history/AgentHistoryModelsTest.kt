package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.item.ContextCompactionHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.TurnTimeMarkerHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val agentHistoryModelsTest by testSuite {
    test("projects only multi-item sealed work runs") {
        val projected = projectSealedHistory(
            listOf(
                descriptor(20, HistoryItemKind.Message),
                descriptor(19, HistoryItemKind.Reasoning),
                descriptor(18, HistoryItemKind.Patch),
                descriptor(17, HistoryItemKind.Tool),
                descriptor(16, HistoryItemKind.PlanUpdate),
                descriptor(15, HistoryItemKind.Reasoning),
                descriptor(14, HistoryItemKind.ContextCompaction),
                descriptor(13, HistoryItemKind.Tool),
                descriptor(12, HistoryItemKind.Patch),
                descriptor(11, HistoryItemKind.RequestUserInput),
                descriptor(10, HistoryItemKind.Tool),
            ),
        )

        assertEquals(8, projected.size)
        assertStableDescriptor(projected[0], index = 20, kind = HistoryItemKind.Message)
        assertGroup(projected[1], indexes = listOf(19, 18, 17))
        assertStableDescriptor(projected[2], index = 16, kind = HistoryItemKind.PlanUpdate)
        assertStableDescriptor(projected[3], index = 15, kind = HistoryItemKind.Reasoning)
        assertStableDescriptor(projected[4], index = 14, kind = HistoryItemKind.ContextCompaction)
        assertGroup(projected[5], indexes = listOf(13, 12))
        assertStableDescriptor(projected[6], index = 11, kind = HistoryItemKind.RequestUserInput)
        assertStableDescriptor(projected[7], index = 10, kind = HistoryItemKind.Tool)
    }

    test("keeps the newest foldable prefix open") {
        val projection = projectNewestHistory(
            listOf(
                descriptor(30, HistoryItemKind.Reasoning),
                descriptor(29, HistoryItemKind.Tool),
                descriptor(28, HistoryItemKind.Message),
                descriptor(27, HistoryItemKind.Tool),
                descriptor(26, HistoryItemKind.Patch),
            ),
        )

        assertEquals(
            listOf(30, 29),
            projection.openItems.map { item ->
                assertIs<HistoryProjectionItem.Stable>(item).descriptor.index
            },
        )
        assertStableDescriptor(projection.sealedItems[0], 28, HistoryItemKind.Message)
        assertGroup(projection.sealedItems[1], indexes = listOf(27, 26))
    }

    test("loads sparse history items newest-first and retains item state") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("one")
                storage.stable[4] = userMessage("four")
                storage.stable[9] = userMessage("nine")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)
                val window = model.historyItems.value
                assertEquals(
                    listOf(9, 4, 1),
                    (0 until window.size).map { position -> window.peek(position).storageIndex },
                )

                val message = assertIs<MessageHistoryItemViewModel>(window[0])
                val ready = awaitState(message.state) { state -> state is MessageHistoryItemState.Ready }
                assertEquals(9, message.index)
                assertIs<StableCleanEvent.UserMessage>(
                    (ready as MessageHistoryItemState.Ready).event,
                )
                assertSame(message, model.historyItems.value.peek(0))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("folded groups load headers first and details only on expansion") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = textTool("older")
                storage.stable[2] = textTool("newer")
                storage.stable[3] = userMessage("breaker")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 2, hasOlder = false)
                val window = model.historyItems.value
                val group = assertIs<WorkGroupHistoryItemViewModel>(window[1])

                assertIs<WorkGroupHistoryItemState.Collapsed>(
                    awaitState(group.state) { state ->
                        state is WorkGroupHistoryItemState.Collapsed
                    },
                )
                group.expand()
                val expanded = awaitState(group.state) { state ->
                    state is WorkGroupHistoryItemState.Expanded
                } as WorkGroupHistoryItemState.Expanded
                assertEquals(listOf(2, 1), expanded.children.map { child -> child.index })

                val newestTool = assertIs<ToolHistoryItemViewModel>(expanded.children[0])
                assertIs<ToolHistoryItemState.Collapsed>(
                    awaitState(newestTool.state) { state ->
                        state is ToolHistoryItemState.Collapsed
                    },
                )
                newestTool.expand()
                assertIs<ToolHistoryItemState.Expanded>(
                    awaitState(newestTool.state) { state ->
                        state is ToolHistoryItemState.Expanded
                    },
                )

                group.collapse()
                assertIs<WorkGroupHistoryItemState.Collapsed>(group.state.value)
                assertIs<ToolHistoryItemState.Collapsed>(newestTool.state.value)

                group.expand()
                val expandedAgain = awaitState(group.state) { state ->
                    state is WorkGroupHistoryItemState.Expanded
                } as WorkGroupHistoryItemState.Expanded
                assertNotSame(newestTool, expandedAgain.children[0])
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("sealing the newest run releases its previously materialized item VMs") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = textTool("older")
                storage.stable[2] = textTool("newer")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 2, hasOlder = false)
                val newestTool = assertIs<ToolHistoryItemViewModel>(model.historyItems.value[0])
                awaitState(newestTool.state) { state ->
                    state is ToolHistoryItemState.Collapsed
                }
                newestTool.expand()
                assertIs<ToolHistoryItemState.Expanded>(
                    awaitState(newestTool.state) { state ->
                        state is ToolHistoryItemState.Expanded
                    },
                )

                runtime.modify { storage ->
                    storage.stable[3] = userMessage("breaker")
                }
                val sealedWindow = model.awaitHistoryItems { window ->
                    window.size == 2 &&
                        window.peek(0) is MessageHistoryItemViewModel &&
                        window.peek(1) is WorkGroupHistoryItemViewModel
                }

                assertIs<ToolHistoryItemState.Collapsed>(newestTool.state.value)
                val group = assertIs<WorkGroupHistoryItemViewModel>(sealedWindow[1])
                awaitState(group.state) { state ->
                    state is WorkGroupHistoryItemState.Collapsed
                }
                group.expand()
                val expanded = awaitState(group.state) { state ->
                    state is WorkGroupHistoryItemState.Expanded
                } as WorkGroupHistoryItemState.Expanded
                assertNotSame(newestTool, expanded.children.first())
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("uses zero for the first item and exact non-negative elapsed values") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("one")
                storage.timestamp[1] = Instant.fromEpochSeconds(100)
                storage.stable[4] = userMessage("four")
                storage.timestamp[4] = Instant.fromEpochSeconds(111)
                storage.stable[9] = userMessage("nine")
                storage.timestamp[9] = Instant.fromEpochSeconds(125)
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)
                val window = model.historyItems.value
                val newest = assertIs<MessageHistoryItemViewModel>(window[0])
                val middle = assertIs<MessageHistoryItemViewModel>(window[1])
                val oldest = assertIs<MessageHistoryItemViewModel>(window[2])

                assertEquals(14.seconds, messageReady(newest).elapsed)
                assertEquals(11.seconds, messageReady(middle).elapsed)
                assertEquals(Duration.ZERO, messageReady(oldest).elapsed)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("projects a completed turn footer into the stable timeline") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                val firstTurn = KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    turnId = "turn-1",
                )
                storage.initialize(firstTurn)
                val turnStart = storage.timestamp[0]
                storage.timestamp[1] = turnStart + 10.seconds
                storage.stable[1] = userMessage("first")
                storage.timestamp[2] = turnStart + 30.seconds
                storage.stable[2] = textTool("first-tool")
                storage.timestamp[3] = turnStart + 40.seconds
                storage.settings[3] = firstTurn.copy(turnId = "turn-2")
                storage.timestamp[4] = turnStart + 50.seconds
                storage.stable[4] = userMessage("second")
            }
            val model = createAgentHistoryViewModel(
                agentState = runtime,
                ownerScope = supervisorChildScope(),
                runningTurn = MutableStateFlow<Job?>(null),
            )
            try {
                model.awaitReady(itemCount = 5, hasOlder = false)
                val window = model.historyItems.value
                val latestFooter = assertIs<TurnTimeMarkerHistoryItemViewModel>(window.peek(0))
                assertEquals(3, latestFooter.markerIndex)
                assertEquals(4, latestFooter.endIndex)
                assertEquals(10.seconds, latestFooter.duration)

                val firstFooter = assertIs<TurnTimeMarkerHistoryItemViewModel>(window.peek(2))
                assertEquals(0, firstFooter.markerIndex)
                assertEquals(2, firstFooter.endIndex)
                assertEquals(30.seconds, firstFooter.duration)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("invalidates the old window and item generation after revert") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(20) { position ->
                    storage.stable[position + 1] = userMessage("$position")
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 20, hasOlder = false)
                val oldWindow = model.historyItems.value
                val oldGeneration = oldWindow.generation
                val oldIndex = oldWindow.peek(0).storageIndex

                runtime.modify { storage -> storage.revert(10) }
                val replacement = model.awaitHistoryItems { window ->
                    window.generation > oldGeneration
                }
                model.awaitReady(itemCount = 9, hasOlder = false)

                assertEquals(oldGeneration + 1, replacement.generation)
                assertEquals(9, model.historyItems.value.peek(0).storageIndex)
                assertFalse(model.contains(oldGeneration, oldIndex))
                assertEquals(20, oldWindow.size)
                repeat(oldWindow.size) { position -> oldWindow.peek(position) }
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("retains stable item identity while loading older batches") {
        coroutineScope {
            val itemCount = 130
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(itemCount) { position ->
                    storage.stable[position + 1] = userMessage("$position")
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 64, hasOlder = true)
                val newest = model.historyItems.value.peek(0)
                val oldest = model.historyItems.value.peek(63)
                model.historyItems.value[63]
                model.awaitReady(itemCount = 128, hasOlder = true)
                assertSame(newest, model.historyItems.value.peek(0))
                assertSame(oldest, model.historyItems.value.peek(63))

                val secondOldest = model.historyItems.value.peek(127)
                model.historyItems.value[127]
                model.awaitReady(itemCount = itemCount, hasOlder = false)
                assertSame(newest, model.historyItems.value.peek(0))
                assertSame(secondOldest, model.historyItems.value.peek(127))
                assertEquals(130, model.historyItems.value.peek(0).storageIndex)
                assertEquals(1, model.historyItems.value.peek(129).storageIndex)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("publishes pending tools independently from stable history") {
        coroutineScope {
            val pending = PendingCustomToolEvent(
                callId = "pending-call",
                name = "pending-tool",
                input = "input",
            )
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                val settings = KodexAgentSettings(model = OpenAiModelId("test-model"))
                storage.initialize(settings)
                storage.unstable[1] = listOf(pending)
                storage.settings[2] = settings.copy(threadName = "updated")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 0, hasOlder = false)
                withTimeout(5.seconds) {
                    model.pendingTools.first { events -> events == listOf(pending) }
                }
                assertEquals(listOf(pending), model.pendingTools.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("owns follow-latest interaction intent") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 0, hasOlder = false)
                model.scrollInteractionSource.tryEmit(
                    ScrollInteraction(
                        source = ScrollInputSource.Pointer,
                        orientation = ScrollOrientation.Vertical,
                        requestedDelta = -1,
                        consumedDelta = 0,
                    ),
                )
                assertTrue(model.followsLatest)

                model.scrollInteractionSource.tryEmit(
                    ScrollInteraction(
                        source = ScrollInputSource.Pointer,
                        orientation = ScrollOrientation.Vertical,
                        requestedDelta = -1,
                        consumedDelta = -1,
                    ),
                )
                assertFalse(model.followsLatest)
                model.notifyContentChanged()
                assertFalse(model.followsLatest)
                model.requestScrollToLatest()
                assertTrue(model.followsLatest)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun descriptor(index: Int, kind: HistoryItemKind): HistoryItemDescriptor =
    HistoryItemDescriptor(index = index, kind = kind, elapsed = Duration.ZERO)

private fun assertStableDescriptor(
    item: HistoryProjectionItem,
    index: Int,
    kind: HistoryItemKind,
) {
    val stable = assertIs<HistoryProjectionItem.Stable>(item)
    assertEquals(index, stable.descriptor.index)
    assertEquals(kind, stable.descriptor.kind)
}

private fun assertGroup(item: HistoryProjectionItem, indexes: List<Int>) {
    val group = assertIs<HistoryProjectionItem.WorkGroup>(item)
    assertEquals(indexes, group.descriptors.map { descriptor -> descriptor.index })
    assertEquals(indexes.size, group.descriptors.size)
}

private suspend fun AgentHistoryViewModel.awaitReady(
    itemCount: Int,
    hasOlder: Boolean? = null,
) {
    withContext(kotlinx.coroutines.Dispatchers.Default) {
        withTimeout(5.seconds) {
            val state = loadState.first { current ->
                current is AgentHistoryLoadState.Failed ||
                    (
                        current is AgentHistoryLoadState.Ready &&
                            historyItems.value.size == itemCount &&
                            (hasOlder == null || current.hasOlder == hasOlder)
                        )
            }
            if (state is AgentHistoryLoadState.Failed) {
                error("History loading failed: ${state.message}")
            }
        }
    }
}

private suspend fun AgentHistoryViewModel.awaitHistoryItems(
    predicate: (HistoryItemWindow) -> Boolean,
): HistoryItemWindow = withContext(kotlinx.coroutines.Dispatchers.Default) {
    withTimeout(5.seconds) {
        historyItems.first(predicate)
    }
}

private suspend fun <T> awaitState(
    state: StateFlow<T>,
    predicate: (T) -> Boolean,
): T = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) {
        state.first(predicate)
    }
}

private suspend fun messageReady(
    item: MessageHistoryItemViewModel,
): MessageHistoryItemState.Ready =
    awaitState(item.state) { state -> state is MessageHistoryItemState.Ready }
        as MessageHistoryItemState.Ready

private val HistoryItemViewModel.storageIndex: Int
    get() = when (this) {
        is MessageHistoryItemViewModel -> index
        is ReasoningHistoryItemViewModel -> index
        is ToolHistoryItemViewModel -> index
        is RequestUserInputHistoryItemViewModel -> index
        is PatchHistoryItemViewModel -> index
        is PlanUpdateHistoryItemViewModel -> index
        is ContextCompactionHistoryItemViewModel -> index
        is WorkGroupHistoryItemViewModel -> indexRange.last
        is TurnTimeMarkerHistoryItemViewModel ->
            error("A turn time marker has no stable storage index.")
    }

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )

private fun textTool(name: String): StableTextToolEvent =
    StableTextToolEvent(
        callId = "call-$name",
        name = name,
        arguments = JsonObject(emptyMap()),
        result = "done",
        success = true,
    )
