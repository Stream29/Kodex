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
                assertEquals(
                    listOf(9, 4, 1),
                    (0 until model.committedItemCount.value).map { position ->
                        model.peek(position).storageIndex
                    },
                )
                assertIs<HistoryItemViewModel.Message>(model.peek(0))
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
                val newest = assertIs<HistoryItemViewModel.Tool>(model.peek(0))
                newest.toggleExpanded()

                model[63]
                model.awaitReady(itemCount = 128, hasOlder = true)
                assertSame(newest, model.peek(0))

                model[127]
                model.awaitReady(itemCount = 130, hasOlder = false)
                assertSame(newest, model.peek(0))
                assertTrue(newest.expanded)
                assertEquals(130, model.peek(0).storageIndex)
                assertEquals(1, model.peek(129).storageIndex)
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
                val newest = model.peek(0)

                while (model.committedItemCount.value < itemCount) {
                    val previousCount = model.committedItemCount.value
                    val previousOldest = model.peek(previousCount - 1)
                    model[previousCount - 1]
                    val expectedCount = minOf(previousCount + 64, itemCount)
                    model.awaitReady(
                        itemCount = expectedCount,
                        hasOlder = expectedCount < itemCount,
                    )
                    assertSame(previousOldest, model.peek(previousCount - 1))
                }

                assertSame(newest, model.peek(0))
                assertEquals(itemCount, model.peek(0).storageIndex)
                assertEquals(1, model.peek(itemCount - 1).storageIndex)
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
                val initialGeneration = model.generation.value
                val oldChild = model.peek(0)

                runtime.modify { storage -> storage.revert(10) }
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.generation.first { generation -> generation > initialGeneration }
                    }
                }
                model.awaitReady(itemCount = 9, hasOlder = false)

                assertFalse(model.contains(initialGeneration, oldChild.storageIndex))
                assertEquals(9, model.peek(0).storageIndex)
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
                        committedItemCount.value == itemCount &&
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
