package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val agentHistoryModelsTest by testSuite {
    test("projects only multi-item sealed work runs") {
        val reasoning = HistoryItemViewModel.Reasoning(index = 19)
        val patch = HistoryItemViewModel.Patch(index = 17)
        val tool = HistoryItemViewModel.Tool(index = 15, initiallyExpanded = true)
        val secondTool = HistoryItemViewModel.Tool(index = 11)
        val secondPatch = HistoryItemViewModel.Patch(index = 10)
        val projected = projectSealedHistory(
            listOf(
                HistoryItemViewModel.Message(index = 20),
                reasoning,
                patch,
                tool,
                HistoryItemViewModel.PlanUpdate(index = 14),
                HistoryItemViewModel.Reasoning(index = 13),
                HistoryItemViewModel.ContextCompaction(index = 12),
                secondTool,
                secondPatch,
                HistoryItemViewModel.RequestUserInput(index = 9),
                HistoryItemViewModel.Tool(index = 8),
            ),
        )

        assertEquals(8, projected.size)
        val firstGroup = assertIs<HistoryItemViewModel.WorkGroup>(projected[1])
        assertEquals(15..19, firstGroup.indexRange)
        assertEquals(3, firstGroup.itemCount)
        assertSame(reasoning, firstGroup.childAt(0))
        assertSame(patch, firstGroup.childAt(1))
        assertSame(tool, firstGroup.childAt(2))
        assertTrue(tool.expanded)
        assertFalse(firstGroup.expanded)
        firstGroup.toggleExpanded()
        assertTrue(firstGroup.expanded)

        assertIs<HistoryItemViewModel.PlanUpdate>(projected[2])
        assertIs<HistoryItemViewModel.Reasoning>(projected[3])
        assertIs<HistoryItemViewModel.ContextCompaction>(projected[4])
        val secondGroup = assertIs<HistoryItemViewModel.WorkGroup>(projected[5])
        assertSame(secondTool, secondGroup.childAt(0))
        assertSame(secondPatch, secondGroup.childAt(1))
        assertIs<HistoryItemViewModel.RequestUserInput>(projected[6])
        assertIs<HistoryItemViewModel.Tool>(projected[7])
    }

    test("keeps the newest foldable prefix open") {
        val newestReasoning = HistoryItemViewModel.Reasoning(index = 30)
        val newestTool = HistoryItemViewModel.Tool(index = 29)
        val sealedTool = HistoryItemViewModel.Tool(index = 27)
        val sealedPatch = HistoryItemViewModel.Patch(index = 26)

        val projection = projectNewestHistory(
            listOf(
                newestReasoning,
                newestTool,
                HistoryItemViewModel.Message(index = 28),
                sealedTool,
                sealedPatch,
            ),
        )

        assertEquals(2, projection.openItems.size)
        assertSame(newestReasoning, projection.openItems[0])
        assertSame(newestTool, projection.openItems[1])
        assertEquals(2, projection.sealedItems.size)
        assertIs<HistoryItemViewModel.Message>(projection.sealedItems[0])
        val group = assertIs<HistoryItemViewModel.WorkGroup>(projection.sealedItems[1])
        assertSame(sealedTool, group.childAt(0))
        assertSame(sealedPatch, group.childAt(1))
    }

    test("loads sparse history items newest-first") {
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
                val historyItems = model.historyItems.value
                assertEquals(
                    listOf(9, 4, 1),
                    (0 until historyItems.size).map { position ->
                        historyItems.peek(position).storageIndex
                    },
                )
                assertIs<HistoryItemViewModel.Message>(historyItems.peek(0))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("computes elapsed from exact timestamps at adjacent stable indexes") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("one")
                storage.timestamp[1] = Instant.fromEpochSeconds(100)
                storage.timestamp[2] = Instant.fromEpochSeconds(500)
                storage.stable[4] = userMessage("four")
                storage.timestamp[4] = Instant.fromEpochSeconds(111)
                storage.stable[9] = userMessage("nine")
                storage.timestamp[9] = Instant.fromEpochSeconds(125)
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)
                val window = model.historyItems.value
                val newest = window.peek(0)
                val middle = window.peek(1)
                val oldest = window.peek(2)

                assertEquals(14.seconds, model.elapsedSincePrevious(newest))
                assertEquals(11.seconds, model.elapsedSincePrevious(middle))
                assertEquals(null, model.elapsedSincePrevious(oldest))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("omits elapsed when an exact timestamp is missing or time moves backwards") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("one")
                storage.timestamp[1] = Instant.fromEpochSeconds(100)
                storage.stable[2] = userMessage("two")
                storage.stable[3] = userMessage("three")
                storage.timestamp[3] = Instant.fromEpochSeconds(90)
                storage.stable[4] = userMessage("four")
                storage.timestamp[4] = Instant.fromEpochSeconds(80)
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                val initialState = model.loadState.first { state ->
                    state is AgentHistoryLoadState.Ready || state is AgentHistoryLoadState.Failed
                }
                model.awaitReady(itemCount = 4, hasOlder = false)
                val window = model.historyItems.value

                assertEquals(null, model.elapsedSincePrevious(window.peek(0)))
                assertEquals(null, model.elapsedSincePrevious(window.peek(1)))
                assertEquals(null, model.elapsedSincePrevious(window.peek(2)))
                assertEquals(null, model.elapsedSincePrevious(window.peek(3)))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("projects historical and active turn time markers") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            val runningTurn = MutableStateFlow<Job?>(null)
            runtime.modify { storage ->
                val firstTurn = KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    turnId = "turn-1",
                )
                storage.initialize(firstTurn)
                val initialTimestamp = storage.timestamp[0]
                storage.timestamp[1] = initialTimestamp + 10.seconds
                storage.stable[1] = userMessage("first")
                storage.timestamp[2] = initialTimestamp + 30.seconds
                storage.stable[2] = textTool("first-tool")
                val secondTurnTimestamp = Clock.System.now()
                storage.timestamp[3] = secondTurnTimestamp
                storage.settings[3] = firstTurn.copy(turnId = "turn-2")
                storage.timestamp[4] = secondTurnTimestamp + 50.seconds
                storage.stable[4] = userMessage("second")
            }
            assertEquals("turn-2", runtime.storage.settings[3].turnId)
            assertEquals(3, runtime.storage.settings.floorToIndex(4))
            assertEquals(2, runtime.storage.stable.prevIndex(3))
            assertEquals(4, runtime.latestIndex.value)
            val model = createAgentHistoryViewModel(
                agentState = runtime,
                ownerScope = supervisorChildScope(),
                runningTurn = runningTurn,
            )
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        val state = model.loadState.first { current ->
                            current is AgentHistoryLoadState.Ready ||
                                current is AgentHistoryLoadState.Failed
                        }
                        assertIs<AgentHistoryLoadState.Ready>(state)
                        assertEquals(5, model.historyItems.value.size)
                        assertFalse(state.hasOlder)
                    }
                }
                assertEquals(0, runtime.storage.timestamp.floorToIndex(0))
                val window = model.historyItems.value
                val latest = assertIs<HistoryItemViewModel.TurnTimeMarker>(window.peek(0))
                assertEquals(3, latest.markerIndex)
                assertEquals(4, latest.endIndex)
                assertEquals(50.seconds, latest.duration)

                val footer = assertIs<HistoryItemViewModel.TurnTimeMarker>(window.peek(2))
                assertEquals(0, footer.markerIndex)
                assertEquals(2, footer.endIndex)
                assertEquals(30.seconds, footer.duration)
                val latestStableItem = window.peek(1)
                val initialGeneration = window.generation

                val activeJob = Job()
                runningTurn.value = activeJob
                val active = model.activeTurnDuration.first { state -> state != null }!!
                assertTrue(active < 1.seconds)
                val activeWindow = model.historyItems.first { items ->
                    items.peek(0) !is HistoryItemViewModel.TurnTimeMarker
                }
                assertEquals(initialGeneration, activeWindow.generation)
                assertSame(latestStableItem, activeWindow.peek(0))
                activeJob.cancel()
                val stoppedWindow = model.historyItems.first { items ->
                    items.peek(0) is HistoryItemViewModel.TurnTimeMarker
                }
                assertEquals(initialGeneration, stoppedWindow.generation)
                assertSame(latestStableItem, stoppedWindow.peek(1))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("adds a newly appended turn marker without rebuilding stable history") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        turnId = "turn-1",
                    ),
                )
                storage.timestamp[1] = storage.timestamp[0] + 1.seconds
                storage.stable[1] = userMessage("first")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 2, hasOlder = false)
                val initialWindow = model.historyItems.value
                val initialMarker = initialWindow.peek(0)
                val initialItem = initialWindow.peek(1)
                val firstTurn = runtime.storage.settings[0]

                runtime.modify { storage ->
                    storage.timestamp[2] = storage.timestamp[1] + 1.seconds
                    storage.settings[2] = firstTurn.copy(turnId = "turn-2")
                }
                val updatedWindow = model.awaitHistoryItems { window ->
                    window.size == 2 &&
                        window.peek(0) === initialMarker &&
                        window.peek(1) === initialItem
                }
                val marker = assertIs<HistoryItemViewModel.TurnTimeMarker>(updatedWindow.peek(0))
                assertEquals(0, marker.markerIndex)
                assertEquals(1, marker.endIndex)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("does not materialize duration markers for empty turns") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                val firstTurn = KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    turnId = "turn-1",
                )
                storage.initialize(firstTurn)
                storage.timestamp[1] = storage.timestamp[0] + 1.seconds
                storage.stable[1] = userMessage("first")
                storage.timestamp[2] = storage.timestamp[1] + 1.seconds
                storage.settings[2] = firstTurn.copy(turnId = "turn-2")
                storage.timestamp[3] = storage.timestamp[2] + 1.seconds
                storage.settings[3] = firstTurn.copy(turnId = "turn-3")
                storage.timestamp[4] = storage.timestamp[3] + 1.seconds
                storage.stable[4] = userMessage("third")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                val initialState = model.loadState.first { state ->
                    state is AgentHistoryLoadState.Ready || state is AgentHistoryLoadState.Failed
                }
                model.awaitReady(itemCount = 4, hasOlder = false)
                val window = model.historyItems.value
                val latestMarker = assertIs<HistoryItemViewModel.TurnTimeMarker>(window.peek(0))
                assertEquals(3, latestMarker.markerIndex)
                assertEquals(4, latestMarker.endIndex)
                assertEquals(1.seconds, latestMarker.duration)

                val firstTurnMarker = assertIs<HistoryItemViewModel.TurnTimeMarker>(window.peek(2))
                assertEquals(0, firstTurnMarker.markerIndex)
                assertEquals(1, firstTurnMarker.endIndex)
                assertEquals(1.seconds, firstTurnMarker.duration)
                assertIs<HistoryItemViewModel.Message>(window.peek(1))
                assertIs<HistoryItemViewModel.Message>(window.peek(3))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("keeps a turn marker attached to its materialized page edge") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                val firstTurn = KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    turnId = "turn-1",
                )
                storage.initialize(firstTurn)
                val initialTimestamp = storage.timestamp[0]
                repeat(130) { position ->
                    val index = position + 1
                    storage.timestamp[index] = initialTimestamp + index.seconds
                    storage.stable[index] = userMessage("$index")
                }
                storage.settings[65] = firstTurn.copy(turnId = "turn-2")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                val initialState = model.loadState.first { state ->
                    state is AgentHistoryLoadState.Ready || state is AgentHistoryLoadState.Failed
                }
                model.awaitReady(itemCount = 65, hasOlder = true)
                val initialWindow = model.historyItems.value
                val initialNewest = initialWindow.peek(0)
                initialWindow[initialWindow.size - 1]
                val windowWithOlderMarker = model.awaitHistoryItems { window ->
                    (0 until window.size).any { position ->
                        val item = window.peek(position)
                        item is HistoryItemViewModel.TurnTimeMarker &&
                            item.endIndex == 64
                    }
                }
                assertSame(initialNewest, windowWithOlderMarker.peek(0))
                val marker = (0 until windowWithOlderMarker.size)
                    .map(windowWithOlderMarker::peek)
                    .filterIsInstance<HistoryItemViewModel.TurnTimeMarker>()
                    .single { item -> item.endIndex == 64 }
                assertEquals(0, marker.markerIndex)
                assertEquals(64.seconds, marker.duration)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("work group elapsed equals its expanded child intervals") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("before")
                storage.timestamp[1] = Instant.fromEpochSeconds(100)
                storage.stable[2] = textTool("older")
                storage.timestamp[2] = Instant.fromEpochSeconds(102)
                storage.stable[3] = textTool("newer")
                storage.timestamp[3] = Instant.fromEpochSeconds(105)
                storage.stable[4] = userMessage("breaker")
                storage.timestamp[4] = Instant.fromEpochSeconds(110)
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)
                val group = assertIs<HistoryItemViewModel.WorkGroup>(
                    model.historyItems.value.peek(1),
                )
                val newer = group.childAt(0)
                val older = group.childAt(1)
                val groupElapsed = model.elapsedSincePrevious(group)
                val newerElapsed = model.elapsedSincePrevious(newer)
                val olderElapsed = model.elapsedSincePrevious(older)

                assertEquals(5.seconds, groupElapsed)
                assertEquals(3.seconds, newerElapsed)
                assertEquals(2.seconds, olderElapsed)
                assertEquals(groupElapsed, newerElapsed!! + olderElapsed!!)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("seals the newest run without rebuilding older groups") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = userMessage("older")
                storage.stable[2] = textTool("second")
                storage.stable[3] = textTool("third")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 3, hasOlder = false)
                val initialWindow = model.historyItems.value
                val third = assertIs<HistoryItemViewModel.Tool>(initialWindow.peek(0))
                val second = assertIs<HistoryItemViewModel.Tool>(initialWindow.peek(1))
                second.toggleExpanded()

                runtime.modify { storage ->
                    storage.stable[4] = userMessage("breaker")
                }
                val firstSealedWindow = model.awaitHistoryItems { window ->
                    window.size == 3 &&
                        window.peek(0) is HistoryItemViewModel.Message &&
                        window.peek(1) is HistoryItemViewModel.WorkGroup
                }
                val firstGroup =
                    assertIs<HistoryItemViewModel.WorkGroup>(firstSealedWindow.peek(1))
                assertEquals(2..3, firstGroup.indexRange)
                assertSame(third, firstGroup.childAt(0))
                assertSame(second, firstGroup.childAt(1))
                assertTrue(second.expanded)
                assertTrue(model.contains(firstSealedWindow.generation, 2))
                assertIs<StableTextToolEvent>(model.read(second))

                runtime.modify { storage ->
                    storage.stable[5] = textTool("fifth")
                }
                val appendedWindow = model.awaitHistoryItems { window ->
                    window.size == 4 &&
                        window.peek(0) is HistoryItemViewModel.Tool
                }
                assertSame(firstGroup, appendedWindow.peek(2))

                runtime.modify { storage ->
                    storage.stable[6] = textTool("sixth")
                    storage.stable[7] = userMessage("new breaker")
                }
                val secondSealedWindow = model.awaitHistoryItems { window ->
                    window.size == 5 &&
                        window.peek(0) is HistoryItemViewModel.Message &&
                        window.peek(1) is HistoryItemViewModel.WorkGroup
                }
                val newestGroup =
                    assertIs<HistoryItemViewModel.WorkGroup>(secondSealedWindow.peek(1))
                assertEquals(5..6, newestGroup.indexRange)
                assertSame(firstGroup, secondSealedWindow.peek(3))
                assertTrue(second.expanded)

                val oldGeneration = secondSealedWindow.generation
                runtime.modify { storage -> storage.revert(2) }
                model.awaitHistoryItems { window -> window.generation > oldGeneration }
                model.awaitReady(itemCount = 1, hasOlder = false)
                assertFalse(model.contains(oldGeneration, second.index))
                repeat(secondSealedWindow.size) { position ->
                    secondSealedWindow.peek(position)
                }
                assertSame(second, firstGroup.childAt(1))
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("completed user input breaks ordinary tool groups") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.stable[1] = textTool("one")
                storage.stable[2] = textTool("two")
                storage.stable[3] = StableRequestUserInputToolEvent(
                    callId = "request",
                    arguments = RequestUserInputArgs(questions = emptyList()),
                    result = StableRequestUserInputResult.Failure("cancelled"),
                )
                storage.stable[4] = textTool("four")
                storage.stable[5] = textTool("five")
                storage.stable[6] = userMessage("breaker")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 4, hasOlder = false)
                val window = model.historyItems.value
                assertIs<HistoryItemViewModel.Message>(window.peek(0))
                assertEquals(
                    4..5,
                    assertIs<HistoryItemViewModel.WorkGroup>(window.peek(1)).indexRange,
                )
                assertIs<HistoryItemViewModel.RequestUserInput>(window.peek(2))
                assertEquals(
                    1..2,
                    assertIs<HistoryItemViewModel.WorkGroup>(window.peek(3)).indexRange,
                )
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("extends a batch through the foldable cutoff") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(70) { position ->
                    val index = position + 1
                    storage.stable[index] = if (index in 5..7) {
                        textTool("$index")
                    } else {
                        userMessage("$index")
                    }
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 65, hasOlder = true)
                val initialWindow = model.historyItems.value
                val group = assertIs<HistoryItemViewModel.WorkGroup>(initialWindow.peek(63))
                assertEquals(5..7, group.indexRange)
                assertEquals(3, group.itemCount)
                assertEquals(4, initialWindow.peek(64).storageIndex)

                initialWindow[64]
                model.awaitReady(itemCount = 68, hasOlder = false)
                assertSame(group, model.historyItems.value.peek(63))
                assertEquals(1, model.historyItems.value.peek(67).storageIndex)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("bounds an unbroken older work run") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                repeat(260) { position ->
                    storage.stable[position + 1] = textTool("${position + 1}")
                }
                storage.stable[261] = userMessage("breaker")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 2, hasOlder = true)
                val firstWindow = model.historyItems.value
                val firstGroup = assertIs<HistoryItemViewModel.WorkGroup>(firstWindow.peek(1))
                assertEquals(134..260, firstGroup.indexRange)
                assertEquals(127, firstGroup.itemCount)

                firstWindow[1]
                model.awaitReady(itemCount = 3, hasOlder = true)
                val secondWindow = model.historyItems.value
                val secondGroup = assertIs<HistoryItemViewModel.WorkGroup>(secondWindow.peek(2))
                assertEquals(6..133, secondGroup.indexRange)
                assertEquals(128, secondGroup.itemCount)

                secondWindow[2]
                model.awaitReady(itemCount = 4, hasOlder = false)
                val thirdGroup =
                    assertIs<HistoryItemViewModel.WorkGroup>(model.historyItems.value.peek(3))
                assertEquals(1..5, thirdGroup.indexRange)
                assertEquals(5, thirdGroup.itemCount)
                assertSame(firstGroup, model.historyItems.value.peek(1))
                assertSame(secondGroup, model.historyItems.value.peek(2))
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
                val initialWindow = model.historyItems.value
                val newest = assertIs<HistoryItemViewModel.Tool>(initialWindow.peek(0))
                newest.toggleExpanded()

                initialWindow[63]
                model.awaitReady(itemCount = 128, hasOlder = true)
                assertSame(newest, model.historyItems.value.peek(0))

                model.historyItems.value[127]
                model.awaitReady(itemCount = 130, hasOlder = false)
                val completeWindow = model.historyItems.value
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
                val newest = model.historyItems.value.peek(0)

                while (model.historyItems.value.size < itemCount) {
                    val previousWindow = model.historyItems.value
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
                        model.historyItems.value.peek(previousCount - 1),
                    )
                }

                val completeWindow = model.historyItems.value
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
                val oldWindow = model.historyItems.value
                val initialGeneration = oldWindow.generation
                val oldChild = oldWindow.peek(0)

                runtime.modify { storage -> storage.revert(10) }
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.historyItems.first { window ->
                            window.generation > initialGeneration
                        }
                    }
                }
                model.awaitReady(itemCount = 9, hasOlder = false)

                val replacementWindow = model.historyItems.value
                assertFalse(model.contains(initialGeneration, oldChild.storageIndex))
                assertFailsWith<IllegalStateException> {
                    model.elapsedSincePrevious(oldChild)
                }
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
                        historyItems.value.size == itemCount &&
                        (hasOlder == null || state.hasOlder == hasOlder))
            }
            if (state is AgentHistoryLoadState.Failed) {
                error("History loading failed: ${state.message}")
            }
        }
    }
}

private suspend fun AgentHistoryViewModel.awaitHistoryItems(
    predicate: (HistoryItemWindow) -> Boolean,
): HistoryItemWindow = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) {
        historyItems.first(predicate)
    }
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
        is HistoryItemViewModel.TurnTimeMarker -> error("A turn time marker has no storage index.")
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
