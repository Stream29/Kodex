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
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val agentHistoryModelsTest by testSuite {
    test("loads sparse committed children newest-first") {
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
                model.awaitReady(itemCount = 3)
                val committedItems = model.committedItems.value
                assertEquals(
                    listOf(9, 4, 1),
                    (0 until committedItems.size).map { position ->
                        committedItems.peek(position).storageIndex
                    },
                )
                assertIs<HistoryItemViewModel.Message>(committedItems.peek(0))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("extends only when the loaded older edge is accessed") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(129) { position ->
                    storage.stable[position + 1] = userMessage("$position")
                }
                storage.stable[130] = StableTextToolEvent(
                    callId = "newest-tool",
                    name = "demo",
                    arguments = JsonObject(emptyMap()),
                    result = "done",
                    success = true,
                )
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 64, hasOlder = true)
                val initialWindow = model.committedItems.value
                val newest = assertIs<HistoryItemViewModel.Tool>(initialWindow.peek(0))
                newest.toggleExpanded()

                initialWindow[63]
                model.awaitReady(itemCount = 128, hasOlder = true)
                assertSame(newest, model.committedItems.value.peek(0))

                model.committedItems.value[127]
                model.awaitReady(itemCount = 130, hasOlder = false)
                val completeWindow = model.committedItems.value
                assertSame(newest, completeWindow.peek(0))
                assertTrue(newest.expanded)
                assertEquals(130, completeWindow.peek(0).storageIndex)
                assertEquals(1, completeWindow.peek(129).storageIndex)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("retains materialized children through many demand batches") {
        coroutineScope {
            val itemCount = 1_025
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
                val newest = model.committedItems.value.peek(0)

                while (model.committedItems.value.size < itemCount) {
                    val previousWindow = model.committedItems.value
                    val previousCount = previousWindow.size
                    val previousOldest = previousWindow.peek(previousCount - 1)
                    previousWindow[previousCount - 1]
                    val expectedCount = minOf(previousCount + 64, itemCount)
                    model.awaitReady(
                        itemCount = expectedCount,
                        hasOlder = expectedCount < itemCount,
                    )
                    assertSame(
                        previousOldest,
                        model.committedItems.value.peek(previousCount - 1),
                    )
                }

                val completeWindow = model.committedItems.value
                assertSame(newest, completeWindow.peek(0))
                assertEquals(itemCount, completeWindow.peek(0).storageIndex)
                assertEquals(1, completeWindow.peek(itemCount - 1).storageIndex)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("invalidates every loaded child after history replacement") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(70) { position ->
                    storage.stable[position + 1] = userMessage("$position")
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 64, hasOlder = true)
                val oldWindow = model.committedItems.value
                val initialGeneration = oldWindow.generation
                val oldChild = oldWindow.peek(0)

                runtime.modify { storage -> storage.revert(10) }
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.committedItems.first { window ->
                            window.generation > initialGeneration
                        }
                    }
                }
                model.awaitReady(itemCount = 9, hasOlder = false)

                val replacementWindow = model.committedItems.value
                assertFalse(model.contains(initialGeneration, oldChild.storageIndex))
                assertEquals(initialGeneration + 1, replacementWindow.generation)
                assertEquals(9, replacementWindow.peek(0).storageIndex)
                assertEquals(64, oldWindow.size)
                repeat(oldWindow.size) { position ->
                    oldWindow.peek(position)
                }
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("projects pending tools through later sparse timeline changes") {
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
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.pendingTools.first { events -> events == listOf(pending) }
                    }
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

private suspend fun AgentHistoryViewModel.awaitReady(
    itemCount: Int,
    hasOlder: Boolean? = null,
) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            val state = loadState.first { state ->
                state is AgentHistoryLoadState.Failed ||
                    (state is AgentHistoryLoadState.Ready &&
                        committedItems.value.size == itemCount &&
                        (hasOlder == null || state.hasOlder == hasOlder))
            }
            if (state is AgentHistoryLoadState.Failed) {
                error("History loading failed: ${state.message}")
            }
        }
    }
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

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )
