@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
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
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import kotlin.test.assertNotEquals
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
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
    test("message timestamp reads are exact and reject stale generations without changing content") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            try {
                val session = repository.open(repository.create())
                val time = Instant.parse("2026-09-08T01:02:03Z")
                val userMsg = StableUserMessage(listOf(ContentItem.InputText("readable")))
                session.storage.commit(listOf(userMsg), time + 1.seconds)
                var current = true
                val context = HistoryItemLoadContext(
                    AgentHistorySource(session.storage, session.runtime.latestIndex, session.runtime.state),
                    this, { current },
                )
                val item = MessageHistoryItemViewModelImpl(
                    1,
                    HistoryItemDescriptor(
                        index = 1,
                        source = HistoryItemSource.Index,
                        kind = HistoryItemKind.Message,
                        elapsed = Duration.ZERO,
                        event = userMsg,
                        createdAt = time + 1.seconds,
                    ),
                    context,
                )
                item.ensureLoaded()
                val ready = awaitState(item.state) { it !is MessageHistoryItemState.Loading }
                assertIs<MessageHistoryItemState.Ready>(ready)
                assertNotEquals(time, item.readTimestamp())
                assertEquals(time + 1.seconds, item.readTimestamp())
                current = false
                kotlin.test.assertFailsWith<IllegalStateException> { item.readTimestamp() }
                assertSame(ready, item.state.value)
            } finally {
                repository.cancelAndJoin()
            }
        }
    }

    test("invalid suggestion event payload becomes a load failure rather than a rejected decision") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            try {
                val session = repository.open(repository.create())
                val descriptor = HistoryItemDescriptor(
                    99,
                    HistoryItemSource.Index,
                    HistoryItemKind.SuggestSubagentTask,
                    Duration.ZERO,
                    StableUserMessage(listOf(ContentItem.InputText("unexpected"))),
                )
                val context = HistoryItemLoadContext(
                    AgentHistorySource(session.storage, session.runtime.latestIndex, session.runtime.state),
                    this, { true },
                )
                val item = SuggestSubagentTaskHistoryItemViewModelImpl(99, descriptor, context)
                item.ensureLoaded()
                assertEquals(
                    SuggestSubagentTaskHistoryItemState.Failed,
                    awaitState(item.state) { it !is SuggestSubagentTaskHistoryItemState.Loading },
                )
            } finally {
                repository.cancelAndJoin()
            }
        }
    }

    test("persisted suggestions load as independent readonly items between work groups") {
        for (result in listOf(
            """{"type":"completed","response":{"type":"accepted","sessions":[{"uri":"file:///tmp/synthetic/1","name":"Worker"}],"decision":"accepted"}}""",
            """{"type":"completed","response":{"type":"rejected","feedback":"Narrow scope","decision":"rejected"}}""",
            """{"type":"failure","message":"Hook stopped suggestion"}""",
        )) {
            coroutineScope {
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                try {
                    val runtime = repository.open(repository.create()).runtime
                    val event = OpenAiJsonCodec.decodeFromString(
                        CleanIndexEntry.serializer(),
                        """{"type":"suggest_subagent_task_tool_event","call_id":"suggest","arguments":{"tasks":[{"name":"Worker","prompt":"Inspect tests."}]},"result":$result}""",
                    ) as StableSuggestSubagentTaskToolEvent
                    runtime.modify { storage ->
                        storage.initialize(KodexAgentSettings(OpenAiModelId("test")))
                        storage.commit(userMessage("Start"))
                        storage.commit(textTool("before"))
                        storage.commit(event)
                        storage.commit(textTool("after"))
                        storage.commit(userMessage("End"))
                    }
                    val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
                    try {
                        model.awaitReady(itemCount = 5, hasOlder = false)
                        val window = model.historyItems.value
                        assertEquals(4..4, assertIs<WorkGroupHistoryItemViewModel>(window[1]).indexRange)
                        val item = assertIs<SuggestSubagentTaskHistoryItemViewModel>(window[2])
                        val ready = awaitState(item.state) { it !is SuggestSubagentTaskHistoryItemState.Loading }
                        assertEquals(event, assertIs<SuggestSubagentTaskHistoryItemState.Ready>(ready).event)
                        assertEquals(3, item.index)
                        assertEquals(2..2, assertIs<WorkGroupHistoryItemViewModel>(window[3]).indexRange)
                    } finally {
                        model.close()
                    }
                } finally {
                    repository.cancelAndJoin()
                }
            }
        }
    }

    test("loads sparse history items newest-first and retains item state") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                for (i in 1..9) {
                    when (i) {
                        1 -> storage.commit(userMessage("one"))
                        4 -> storage.commit(userMessage("four"))
                        9 -> storage.commit(userMessage("nine"))
                        else -> storage.commit(CommitTimestamp(Instant.fromEpochSeconds(0)))
                    }
                }
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
                storage.commit(CommitTimestamp(Instant.fromEpochSeconds(0)))
                storage.commit(textTool("older"))
                storage.commit(textTool("newer"))
                storage.commit(userMessage("breaker"))
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
                storage.commit(userMessage("prompt"))
                storage.commit(textTool("only"))
                storage.commit(userMessage("breaker"))
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
                storage.commit(CommitTimestamp(Instant.fromEpochSeconds(0)))
                storage.commit(textTool("older"))
                storage.commit(textTool("newer"))
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
                    storage.commit(userMessage("breaker"))
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
                    storage.commit(textTool("work-$position"))
                }
                storage.commit(userMessage("previous"))
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 2, hasOlder = false)

                val newIndex = workCount + 2
                runtime.modify { storage ->
                    storage.commit(userMessage("new"))
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

    test("unmeasured History retains recent pages only up to its budget") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 0, hasOlder = false)

                repeat(300) { position ->
                    val index = position + 1
                    runtime.modify { storage ->
                        storage.commit(userMessage("$index"))
                    }
                    val window = model.awaitHistoryItems { history ->
                        history.size > 0 && history.peek(0).storageIndex == index
                    }
                    assertTrue(window.size <= HistoryPaging.MaxSize)
                    assertTrue(window.size >= minOf(index, HistoryPaging.PageSize))
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
                storage.commit(userMessage("previous"))
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 1, hasOlder = false)

                runtime.modify { storage ->
                    storage.commit(textTool("new"))
                }

                val window = model.awaitHistoryItems { history ->
                    history.size > 0 && history.peek(0) is ToolHistoryItemViewModel
                }
                assertEquals(2, window.size)
                assertFalse(window.hasOlder)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("a large append never joins ranges across unread history") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                for (index in 1..20) storage.commit(userMessage("$index"))
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(20, hasOlder = false)
                runtime.modify { storage ->
                    for (index in 21..400) storage.commit(userMessage("$index"))
                }
                val window = model.awaitHistoryItems { it.size > 0 && it.peek(0).storageIndex == 400 }
                assertEquals(HistoryPaging.InitialLoadSize, window.size)
                assertTrue(window.hasOlder)
                assertEquals(
                    (400 downTo 400 - window.size + 1).toList(),
                    List(window.size) { window.peek(it).storageIndex },
                )
                model.reportViewport(window, listOf(window.peek(window.size - 1)))
                val next = model.awaitHistoryItems { it !== window }
                assertTrue(
                    List(next.size) { next.peek(it).storageIndex }
                        .zipWithNext().all { (newer, older) -> newer - older == 1 },
                )
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
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.commit(listOf(userMessage("one"), CommitTimestamp(Instant.fromEpochSeconds(100))))
                storage.commit(CommitTimestamp(Instant.fromEpochSeconds(101)))
                storage.commit(CommitTimestamp(Instant.fromEpochSeconds(102)))
                storage.commit(listOf(userMessage("four"), CommitTimestamp(Instant.fromEpochSeconds(111))))
                for (i in 5..8) storage.commit(CommitTimestamp(Instant.fromEpochSeconds(111)))
                storage.commit(listOf(userMessage("nine"), CommitTimestamp(Instant.fromEpochSeconds(125))))
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
                val turnStart = storage.getCommitExact(0)!!.createdAt
                storage.commit(listOf(CommitTimestamp(turnStart + 10.seconds), userMessage("first")))
                storage.commit(listOf(CommitTimestamp(turnStart + 30.seconds), textTool("first-tool")))
                storage.commit(
                    listOf(
                        CommitTimestamp(turnStart + 40.seconds),
                        StableAssistantMessage(
                            content = listOf(ContentItem.OutputText("first answer")),
                        ),
                    ),
                )
                storage.commit(listOf(CommitTimestamp(turnStart + 50.seconds), userMessage("second")))
                storage.commit(
                    listOf(
                        CommitTimestamp(turnStart + 60.seconds),
                        StableAssistantMessage(
                            content = listOf(ContentItem.OutputText("second answer")),
                        ),
                    ),
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
                val turnStart = storage.getCommitExact(0)!!.createdAt
                storage.commit(listOf(CommitTimestamp(turnStart + 1.seconds), userMessage("first")))
                storage.commit(
                    listOf(
                        CommitTimestamp(turnStart + 2.seconds),
                        StableAssistantMessage(
                            content = listOf(ContentItem.OutputText("final")),
                        ),
                    ),
                )
                storage.commit(listOf(CommitTimestamp(turnStart + 3.seconds), userMessage("steer one")))
                storage.commit(listOf(CommitTimestamp(turnStart + 4.seconds), userMessage("steer two")))
                storage.commit(
                    listOf(
                        CommitTimestamp(turnStart + 5.seconds),
                        StableAssistantMessage(
                            content = listOf(ContentItem.OutputText("next final")),
                        ),
                    ),
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
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                repeat(20) { position ->
                    storage.commit(userMessage("$position"))
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

    test("retains stable item identity while portable viewport demands older items") {
        coroutineScope {
            val itemCount = 320
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                repeat(itemCount) { position ->
                    storage.commit(userMessage("$position"))
                }
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = HistoryPaging.InitialLoadSize, hasOlder = true)
                val newest = model.historyItems.value.peek(0)
                model.reportViewport(model.historyItems.value, listOf(newest))
                model.historyItems.value.requestOlder()
                model.awaitReady(itemCount = HistoryPaging.InitialLoadSize + HistoryPaging.PageSize, hasOlder = true)
                assertSame(newest, model.historyItems.value.peek(0))

                val secondOldest = model.historyItems.value.peek(1)
                model.reportViewport(model.historyItems.value, listOf(newest, secondOldest))
                model.historyItems.value.requestOlder()
                model.awaitReady(itemCount = itemCount, hasOlder = false)
                assertSame(newest, model.historyItems.value.peek(0))
                assertSame(secondOldest, model.historyItems.value.peek(1))
                assertEquals(itemCount, model.historyItems.value.peek(0).storageIndex)
                assertEquals(1, model.historyItems.value.peek(itemCount - 1).storageIndex)
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
                storage.commit(UnstableSnapshot(listOf(pending)))
                storage.commit(settings.copy(threadName = "updated"))
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

    test("owns portable follow-latest intent") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitReady(itemCount = 0, hasOlder = false)
                assertTrue(model.followsLatest.value)
                model.setFollowsLatest(model.historyItems.value, false)
                assertFalse(model.followsLatest.value)
                model.reportViewport(model.historyItems.value, emptyList())
                assertFalse(model.followsLatest.value)
                model.requestScrollToLatest()
                assertTrue(model.followsLatest.value)
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
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.commit(userMessage("one"))
                storage.commit(userMessage("two"))
                storage.commit(userMessage("three"))
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
                assertFalse(model.followsLatest.value)
                assertEquals(1, older.peek(0).storageIndex)

                runtime.modify { storage ->
                    storage.commit(userMessage("four"))
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
                assertTrue(model.followsLatest.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("checks out a compaction entry at its visible compaction result") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                storage.commit(userMessage("prompt"))
                storage.commit(StableContextCompaction(encryptedContent = "encrypted"))
                storage.commit(userMessage("after"))
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
                assertEquals(2, checkedOut.peek(0).storageIndex)
                assertFalse(model.followsLatest.value)
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
                reportViewport(window, listOf(window.peek(0)))
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
        is SuggestSubagentTaskHistoryItemViewModel -> index
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
