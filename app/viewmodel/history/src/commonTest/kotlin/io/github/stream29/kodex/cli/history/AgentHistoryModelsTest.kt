package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
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
    test("loads sparse history items newest-first and retains item state") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.index[1] = userMessage("one")
                storage.index[4] = userMessage("four")
                storage.index[9] = userMessage("nine")
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
                assertIs<StableUserMessage>(
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
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.work[2] = textTool("older")
                storage.work[3] = textTool("newer")
                storage.index[4] = userMessage("breaker")
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
                assertEquals(listOf(3, 2), expanded.children.map { child -> child.index })

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

    test("expands a singleton sealed work group") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.index[1] = userMessage("prompt")
                storage.work[2] = textTool("only")
                storage.index[3] = userMessage("breaker")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)
                val group = assertIs<WorkGroupHistoryItemViewModel>(
                    model.historyItems.value[1],
                )
                assertEquals(1, group.itemCount)
                awaitState(group.state) { it is WorkGroupHistoryItemState.Collapsed }
                group.expand()
                val expanded = awaitState(group.state) { state ->
                    state is WorkGroupHistoryItemState.Expanded
                } as WorkGroupHistoryItemState.Expanded
                assertEquals(listOf(2), expanded.children.map { child -> child.index })
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
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.work[2] = textTool("older")
                storage.work[3] = textTool("newer")
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
                    storage.index[4] = userMessage("breaker")
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

    test("publishes a new index event without a following work event") {
        coroutineScope {
            val workCount = 2_000
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                repeat(workCount) { position ->
                    storage.work[position + 1] = textTool("work-$position")
                }
                storage.index[workCount + 1] = userMessage("previous")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 2, hasOlder = false)

                val newIndex = workCount + 2
                runtime.modify { storage ->
                    storage.index[newIndex] = userMessage("new")
                }

                val window = model.awaitHistoryItems { history ->
                    history.size >= 1 && history.peek(0).storageIndex == newIndex
                }
                assertIs<MessageHistoryItemViewModel>(window.peek(0))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("unmeasured History does not retain obsolete latest chunks") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 0, hasOlder = false)

                repeat(20) { position ->
                    val index = position + 1
                    runtime.modify { storage ->
                        storage.index[index] = userMessage("$index")
                    }
                    val window = model.awaitHistoryItems { history ->
                        history.size > 0 && history.peek(0).storageIndex == index
                    }
                    assertEquals(1, window.size)
                }
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("keeps a newly appended work event materialized in the open prefix") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.index[1] = userMessage("previous")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 1, hasOlder = false)

                runtime.modify { storage ->
                    storage.work[2] = textTool("new")
                }

                val window = model.awaitHistoryItems { history ->
                    history.size > 0 && history.peek(0) is ToolHistoryItemViewModel
                }
                assertEquals(1, window.size)
                assertTrue(window.hasOlder)
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
                storage.index[1] = userMessage("one")
                storage.timestamp[1] = Instant.fromEpochSeconds(100)
                storage.index[4] = userMessage("four")
                storage.timestamp[4] = Instant.fromEpochSeconds(111)
                storage.index[9] = userMessage("nine")
                storage.timestamp[9] = Instant.fromEpochSeconds(125)
            }
            val model = createAgentHistoryViewModel(
                agentState = runtime,
                ownerScope = supervisorChildScope(),
                runningTurn = MutableStateFlow(Job()),
            )
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

    test("attaches a completed turn footer to its final assistant message") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                val firstTurn = KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                )
                storage.initialize(firstTurn)
                val turnStart = storage.timestamp[0]
                storage.timestamp[1] = turnStart + 10.seconds
                storage.index[1] = userMessage("first")
                storage.timestamp[2] = turnStart + 30.seconds
                storage.work[2] = textTool("first-tool")
                storage.timestamp[3] = turnStart + 40.seconds
                storage.index[3] = StableAssistantMessage(
                    content = listOf(ContentItem.OutputText("first answer")),
                )
                storage.timestamp[4] = turnStart + 50.seconds
                storage.index[4] = userMessage("second")
                storage.timestamp[5] = turnStart + 60.seconds
                storage.index[5] = StableAssistantMessage(
                    content = listOf(ContentItem.OutputText("second answer")),
                )
            }
            val model = createAgentHistoryViewModel(
                agentState = runtime,
                ownerScope = supervisorChildScope(),
                runningTurn = MutableStateFlow<Job?>(null),
            )
            try {
                model.awaitReady(itemCount = 5, hasOlder = false)
                val window = model.historyItems.value
                val latestFinal = assertIs<MessageHistoryItemViewModel>(window[0])
                assertEquals(5, latestFinal.index)
                assertEquals(10.seconds, messageReady(latestFinal).turnDuration)

                val firstFinal = assertIs<MessageHistoryItemViewModel>(window[2])
                assertEquals(3, firstFinal.index)
                assertEquals(30.seconds, messageReady(firstFinal).turnDuration)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("consecutive steer messages do not create an extra turn footer") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.index[1] = userMessage("first")
                storage.index[2] = StableAssistantMessage(
                    content = listOf(ContentItem.OutputText("final")),
                )
                storage.index[3] = userMessage("steer one")
                storage.index[4] = userMessage("steer two")
                storage.index[5] = StableAssistantMessage(
                    content = listOf(ContentItem.OutputText("next final")),
                )
                val turnStart = storage.timestamp[0]
                for (index in 1..5) {
                    storage.timestamp[index] = turnStart + index.toLong().seconds
                }
            }
            val model = createAgentHistoryViewModel(
                agentState = runtime,
                ownerScope = supervisorChildScope(),
                runningTurn = MutableStateFlow<Job?>(null),
            )
            try {
                model.awaitReady(itemCount = 5, hasOlder = false)
                val window = model.historyItems.value
                val newestFinal = assertIs<MessageHistoryItemViewModel>(window[0])
                val firstFinal = assertIs<MessageHistoryItemViewModel>(window[3])
                assertEquals(2.seconds, messageReady(newestFinal).turnDuration)
                assertEquals(1.seconds, messageReady(firstFinal).turnDuration)
                assertEquals(
                    listOf(null, null),
                    listOf(
                        messageReady(assertIs<MessageHistoryItemViewModel>(window[1])).turnDuration,
                        messageReady(assertIs<MessageHistoryItemViewModel>(window[2])).turnDuration,
                    ),
                )
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
                    storage.index[position + 1] = userMessage("$position")
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

    test("retains stable item identity while LazyColumn demands older items") {
        coroutineScope {
            val itemCount = 3
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(itemCount) { position ->
                    storage.index[position + 1] = userMessage("$position")
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 1, hasOlder = true)
                val newest = model.historyItems.value.peek(0)
                model.historyItems.value.requestOlder()
                model.awaitReady(itemCount = 2, hasOlder = true)
                assertSame(newest, model.historyItems.value.peek(0))

                val secondOldest = model.historyItems.value.peek(1)
                model.historyItems.value.requestOlder()
                model.awaitReady(itemCount = itemCount, hasOlder = false)
                assertSame(newest, model.historyItems.value.peek(0))
                assertSame(secondOldest, model.historyItems.value.peek(1))
                assertEquals(3, model.historyItems.value.peek(0).storageIndex)
                assertEquals(1, model.historyItems.value.peek(2).storageIndex)
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

    test("checks out index entries as pure bounded History navigation") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.index[1] = userMessage("one")
                storage.index[2] = userMessage("two")
                storage.index[3] = userMessage("three")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)

                model.requestScrollToStorageIndex(1)
                val older = model.awaitHistoryItems { window ->
                    window.hasNewer &&
                        (0 until window.size).any { position ->
                            window.peek(position).storageIndex == 1
                        }
                }
                assertFalse(model.followsLatest)
                assertEquals(1, older.peek(0).storageIndex)

                runtime.modify { storage ->
                    storage.index[4] = userMessage("four")
                }
                model.awaitHistoryItems { window -> window.hasNewer }
                assertEquals(1, model.historyItems.value.peek(0).storageIndex)

                model.requestScrollToStorageIndex(4)
                val latest = model.awaitHistoryItems { window ->
                    !window.hasNewer &&
                        window.size > 0 &&
                        window.peek(0).storageIndex == 4
                }
                assertEquals(4, latest.peek(0).storageIndex)
                assertTrue(model.followsLatest)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("checks out a compaction point at its visible compaction result") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.index[1] = userMessage("prompt")
                storage.index[2] = CleanCompactionPoint
                storage.work[3] = StableContextCompaction(encryptedContent = "encrypted")
                storage.index[4] = userMessage("after")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)

                model.requestScrollToStorageIndex(2)
                val checkedOut = model.awaitHistoryItems { window ->
                    window.hasNewer &&
                        window.size > 0 &&
                        window.peek(0) is ContextCompactionHistoryItemViewModel
                }
                assertEquals(3, checkedOut.peek(0).storageIndex)
                assertFalse(model.followsLatest)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun AgentHistoryViewModel.awaitReady(
    itemCount: Int,
    hasOlder: Boolean? = null,
) {
    withContext(kotlinx.coroutines.Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (true) {
                val state = loadState.first { current ->
                    current == AgentHistoryLoadState.Ready ||
                        current is AgentHistoryLoadState.Failed
                }
                if (state is AgentHistoryLoadState.Failed) {
                    error("History loading failed: ${state.message}")
                }
                val window = historyItems.value
                if (
                    window.size == itemCount &&
                    (hasOlder == null || window.hasOlder == hasOlder)
                ) {
                    return@withTimeout
                }
                val needsEndDiscovery =
                    window.size == itemCount && hasOlder == false && window.hasOlder
                check(
                    (window.size < itemCount || needsEndDiscovery) &&
                        window.hasOlder &&
                        window.size > 0
                ) {
                    "Expected $itemCount History items with hasOlder=$hasOlder, but observed " +
                        "${window.size} items with hasOlder=${window.hasOlder}."
                }
                window.requestOlder()
                loadState.first { updated ->
                    updated is AgentHistoryLoadState.Failed ||
                        (
                            updated == AgentHistoryLoadState.Ready &&
                                (
                                    historyItems.value !== window ||
                                        historyItems.value.hasOlder != window.hasOlder
                                    )
                            )
                }
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
    }

private fun userMessage(text: String): StableUserMessage =
    StableUserMessage(
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
