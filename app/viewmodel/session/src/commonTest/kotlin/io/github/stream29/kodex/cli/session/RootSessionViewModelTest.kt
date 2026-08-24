package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.session.contract.PersistedAgentMaterializationState
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val rootSessionViewModelTest by testSuite {
    test("topology discovery does not eagerly materialize descendants") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = initializedRoot(repository, "Root")
            val root = repository.open(sessionIndex)
            createInitializedChild(root, "Child")
            val probe = SessionViewModelCreationProbe()
            val store = testSessionViewModelRegistry(repository, this, probe)
            val model = store.open(sessionIndex)
            try {
                model.refresh()
                val topology = withTimeout(5.seconds) {
                    model.topology.first { state -> state.nodes.size == 2 }
                }
                val rootNode = topology.nodes.single { node -> node.address == model.rootAgent.address }
                val childNode = topology.nodes.single { node -> node.address != model.rootAgent.address }

                assertEquals(PersistedAgentMaterializationState.Loaded, rootNode.materialization)
                assertEquals(PersistedAgentMaterializationState.Unloaded, childNode.materialization)
                assertEquals(listOf(rootNode.address), probe.agentAddresses)
                assertEquals(listOf(rootNode.address), probe.historyAddresses)

                val selected = model.selectAgent(childNode.address)
                val materialized = model.topology.value.nodes.single { node ->
                    node.address == childNode.address
                }
                assertEquals(PersistedAgentMaterializationState.Loaded, materialized.materialization)
                assertSame(selected, model.selectAgent(childNode.address))
                assertNotSame(model.rootAgent, selected)
                assertEquals(listOf(rootNode.address, childNode.address), probe.agentAddresses)
                assertEquals(listOf(rootNode.address, childNode.address), probe.historyAddresses)
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("large descendant catalogs create only the root projection and one finite history window") {
        coroutineScope {
            val directChildCount = 32
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = initializedRoot(repository, "Large root")
            val root = repository.open(sessionIndex)
            root.runtime.modify { storage ->
                repeat(96) { position ->
                    storage.stable[position + 1] = userMessage("root-$position")
                }
            }
            repeat(directChildCount) { position ->
                val child = createInitializedChild(root, "Child $position")
                createInitializedChild(child, "Grandchild $position")
            }
            val probe = SessionViewModelCreationProbe()
            val store = testSessionViewModelRegistry(repository, this, probe)
            val model = store.open(sessionIndex)
            try {
                val topology = withTimeout(10.seconds) {
                    model.topology.first { state ->
                        state.nodes.size == directChildCount + 1
                    }
                }
                withTimeout(10.seconds) {
                    model.rootAgent.history.loadState.first { state ->
                        state is AgentHistoryLoadState.Ready &&
                            model.rootAgent.history.historyItems.value.size == 64
                    }
                }

                assertEquals(1, probe.agentAddresses.size)
                assertEquals(1, probe.historyAddresses.size)
                assertEquals(model.rootAgent.address, probe.agentAddresses.single())
                assertEquals(model.rootAgent.address, probe.historyAddresses.single())
                assertEquals(64, model.rootAgent.history.historyItems.value.size)
                assertTrue(topology.nodes.drop(1).all { node ->
                    node.materialization == PersistedAgentMaterializationState.Unloaded
                })
                assertTrue(topology.nodes.none { node -> node.depth > 1 })
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("topology preserves repository sibling order and depth-first branches") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = initializedRoot(repository, "Root")
            val root = repository.open(sessionIndex)
            val children = List(12) { position ->
                createInitializedChild(root, "Child $position")
            }
            val grandchild = createInitializedChild(children.first(), "Grandchild 0")
            val probe = SessionViewModelCreationProbe()
            val store = testSessionViewModelRegistry(repository, this, probe)
            val model = store.open(sessionIndex)
            try {
                val rootAddress = model.rootAgent.address
                val firstAddress = model.topology.value.nodes.single { node ->
                    node.threadName == "Child 0"
                }.address

                assertEquals(
                    listOf("Root") + List(12) { position -> "Child $position" },
                    model.topology.value.nodes.map { node -> node.threadName },
                )
                assertEquals(listOf(rootAddress), probe.agentAddresses)

                model.materializeDirectChildren(firstAddress)

                assertEquals(
                    listOf("Root", "Child 0", "Grandchild 0") +
                        List(11) { position -> "Child ${position + 1}" },
                    model.topology.value.nodes.map { node -> node.threadName },
                )
                assertEquals(
                    listOf(0, 1, 2) + List(11) { 1 },
                    model.topology.value.nodes.map { node -> node.depth },
                )
                assertEquals(
                    listOf(
                        rootAddress,
                        AgentAddress(sessionIndex, grandchild.storage.id),
                    ),
                    probe.agentAddresses,
                )
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("direct catalog reconciliation removes stale subtrees and handles entry reuse") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = initializedRoot(repository, "Root")
            val root = repository.open(sessionIndex)
            val first = createInitializedChild(root, "First")
            val firstEntry = root.subagents.list().single()
            createInitializedChild(root, "Second")
            val grandchild = createInitializedChild(first, "Grandchild")
            val probe = SessionViewModelCreationProbe()
            val store = testSessionViewModelRegistry(repository, this, probe)
            val model = store.open(sessionIndex)
            try {
                val firstAddress = model.topology.value.nodes.single { node ->
                    node.threadName == "First"
                }.address
                val grandchildAddress = AgentAddress(sessionIndex, grandchild.storage.id)
                model.materializeDirectChildren(firstAddress)
                val removedSelection = model.selectAgent(grandchildAddress)
                yield()

                assertEquals(
                    listOf("Root", "First", "Grandchild", "Second"),
                    model.topology.value.nodes.map { node -> node.threadName },
                )

                root.subagents.delete(firstEntry)
                model.refresh()

                assertEquals(
                    listOf("Root", "Second"),
                    model.topology.value.nodes.map { node -> node.threadName },
                )
                assertTrue(model.topology.value.nodes.none { node ->
                    node.address == firstAddress || node.address == grandchildAddress
                })
                assertSame(model.rootAgent, model.selectedAgent.value)
                assertEquals(AgentLifecycleState.Closed, removedSelection.lifecycle.value)
                assertEquals(2, model.summary.value.agentCount)

                val replacementEntry = root.subagents.create()
                assertEquals(firstEntry, replacementEntry)
                val replacement = root.subagents.open(replacementEntry)
                initializeAgent(replacement, "Replacement")
                model.refresh()

                assertEquals(
                    listOf("Root", "Replacement", "Second"),
                    model.topology.value.nodes.map { node -> node.threadName },
                )
                assertEquals(listOf(0, 1, 1), model.topology.value.nodes.map { node -> node.depth })
                assertTrue(model.topology.value.nodes.none { node ->
                    node.address == firstAddress || node.address == grandchildAddress
                })
                assertTrue(model.topology.value.nodes.any { node ->
                    node.address == AgentAddress(sessionIndex, replacement.storage.id)
                })
                assertEquals(3, model.summary.value.agentCount)
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("expansion and deep selection materialize exact reusable children and close them on release") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = initializedRoot(repository, "Root")
            val root = repository.open(sessionIndex)
            val first = createInitializedChild(root, "First")
            val sibling = createInitializedChild(root, "Sibling")
            val deep = createInitializedChild(first, "Deep")
            createInitializedChild(deep, "Leaf")
            val probe = SessionViewModelCreationProbe()
            val store = testSessionViewModelRegistry(repository, this, probe)
            val model = store.open(sessionIndex)
            try {
                val rootAddress = model.rootAgent.address
                val direct = model.topology.value.nodes.filter { node ->
                    node.parentAddress == rootAddress
                }
                val firstAddress = direct.single { node -> node.threadName == "First" }.address
                val siblingAddress = direct.single { node -> node.threadName == "Sibling" }.address

                model.materializeDirectChildren(rootAddress)

                assertEquals(
                    setOf(rootAddress, firstAddress, siblingAddress),
                    probe.agentAddresses.toSet(),
                )
                assertEquals(3, probe.agentAddresses.size)
                assertTrue(model.topology.value.nodes.filter { node ->
                    node.parentAddress == rootAddress
                }.all { node ->
                    node.materialization == PersistedAgentMaterializationState.Loaded
                })

                val deepAddress = AgentAddress(
                    sessionIndex = sessionIndex,
                    agentId = deep.storage.id,
                )
                val selected = model.selectAgent(deepAddress)

                assertSame(selected, model.selectAgent(deepAddress))
                assertEquals(4, probe.agentAddresses.size)
                assertEquals(deepAddress, probe.agentAddresses.last())
                assertEquals(
                    PersistedAgentMaterializationState.Loaded,
                    model.topology.value.nodes.single { node ->
                        node.address == deepAddress
                    }.materialization,
                )
                val deepNode = model.topology.value.nodes.single { node ->
                    node.address == deepAddress
                }
                assertEquals(firstAddress, deepNode.parentAddress)
                assertEquals(2, deepNode.depth)
                assertTrue(deepNode.hasChildren)
                assertTrue(model.topology.value.nodes.none { node -> node.depth > 2 })

                assertSame(model.rootAgent, model.selectAgent(rootAddress))
                assertSame(selected, model.selectAgent(deepAddress))
                assertEquals(4, probe.agentAddresses.size)
                assertEquals(4, probe.historyAddresses.size)

                store.release(sessionIndex)

                assertEquals(PersistedSessionLifecycleState.Closed, model.lifecycle.value)
                assertTrue(probe.agents.all { agent ->
                    agent.lifecycle.value == AgentLifecycleState.Closed
                })
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("high frequency child composer and stream events do not publish session projections") {
        coroutineScope {
            val streamOpened = CompletableDeferred<Unit>()
            val allowDeltas = CompletableDeferred<Unit>()
            val deltasEmitted = CompletableDeferred<Unit>()
            val releaseStream = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse {
                    kotlinx.coroutines.flow.flow {
                        val itemId = ResponseItemId("stream-message")
                        val item = ResponseItem.Message(
                            id = itemId,
                            role = MessageRole.Assistant,
                            content = emptyList(),
                        )
                        emit(
                            ResponsesStreamEvent.OutputItemAdded(
                                outputIndex = 0,
                                item = item,
                            ),
                        )
                        streamOpened.complete(Unit)
                        allowDeltas.await()
                        repeat(64) { position ->
                            emit(
                                ResponsesStreamEvent.OutputTextDelta(
                                    itemId = itemId.value,
                                    outputIndex = 0,
                                    contentIndex = 0,
                                    delta = "$position",
                                ),
                            )
                        }
                        deltasEmitted.complete(Unit)
                        releaseStream.await()
                        emit(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = item.copy(content = listOf(ContentItem.OutputText("done"))),
                            ),
                        )
                        emit(
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "stream-response",
                                    endTurn = true,
                                ),
                            ),
                        )
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            val sessionIndex = initializedRoot(repository, "Isolation")
            val store = testSessionViewModelRegistry(repository, this)
            val model = store.open(sessionIndex)
            val summaryEmissions = MutableStateFlow(0)
            val topologyEmissions = MutableStateFlow(0)
            val streamEmissions = MutableStateFlow(0)
            val collectors = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    model.summary.collect {
                        summaryEmissions.update { count -> count + 1 }
                    }
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    model.topology.collect {
                        topologyEmissions.update { count -> count + 1 }
                    }
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    model.rootAgent.history.streamingItem.collect {
                        streamEmissions.update { count -> count + 1 }
                    }
                },
            )
            var submission: Deferred<Unit>? = null
            try {
                model.refresh()
                yield()
                val summaryBeforeComposer = model.summary.value
                val topologyBeforeComposer = model.topology.value
                assertEquals(1, summaryEmissions.value)
                assertEquals(1, topologyEmissions.value)
                repeat(128) { revision ->
                    val text = "draft-$revision"
                    model.rootAgent.composer.update(text, text.length)
                }
                yield()

                assertEquals(128, model.rootAgent.composer.state.value.revision)
                assertSame(summaryBeforeComposer, model.summary.value)
                assertSame(topologyBeforeComposer, model.topology.value)
                assertEquals(1, summaryEmissions.value)
                assertEquals(1, topologyEmissions.value)

                submission = async(Dispatchers.Default) {
                    withContext(NonCancellable) {
                        model.rootAgent.submit(listOf(ContentItem.InputText("stream")))
                    }
                }
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { streamOpened.await() }
                }
                val streamTail = withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        model.rootAgent.history.streamingItem.first { item ->
                            item is HistoryStreamingItem.Output
                        }
                    }
                }
                assertIs<HistoryStreamingItem.Output>(streamTail)
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        model.summary.first { state -> state.rootRunning }
                    }
                    withTimeout(10.seconds) {
                        model.topology.first { state ->
                            state.nodes.single { node ->
                                node.address == model.rootAgent.address
                            }.let { node ->
                                node.running && node.phase == AgentExecutionPhase.Responding
                            }
                        }
                    }
                }
                yield()
                val summaryDuringStream = model.summary.value
                val topologyDuringStream = model.topology.value
                val summaryEmissionsDuringStream = summaryEmissions.value
                val topologyEmissionsDuringStream = topologyEmissions.value
                val streamEmissionsDuringStream = streamEmissions.value

                allowDeltas.complete(Unit)
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { deltasEmitted.await() }
                }
                yield()

                assertEquals(65, streamTail.events.replayCache.size)
                assertSame(summaryDuringStream, model.summary.value)
                assertSame(topologyDuringStream, model.topology.value)
                assertEquals(summaryEmissionsDuringStream, summaryEmissions.value)
                assertEquals(topologyEmissionsDuringStream, topologyEmissions.value)
                assertEquals(streamEmissionsDuringStream, streamEmissions.value)

                releaseStream.complete(Unit)
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { submission.await() }
                    withTimeout(10.seconds) {
                        model.rootAgent.execution.first { execution -> !execution.running }
                    }
                }
                assertTrue(!model.rootAgent.execution.value.running)
            } finally {
                allowDeltas.complete(Unit)
                releaseStream.complete(Unit)
                withContext(NonCancellable) {
                    withTimeout(10.seconds) { submission?.join() }
                    collectors.forEach(Job::cancel)
                    collectors.forEach { collector -> collector.join() }
                    store.shutdown()
                    repository.cancelAndJoin()
                }
            }
        }
    }

    test("a live Agent ViewModel keeps its turn when the view caller is cancelled") {
        coroutineScope {
            val streamOpened = CompletableDeferred<Unit>()
            val releaseStream = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse {
                    kotlinx.coroutines.flow.flow {
                        val item = ResponseItem.Message(
                            id = ResponseItemId("background-message"),
                            role = MessageRole.Assistant,
                            content = emptyList(),
                        )
                        emit(
                            ResponsesStreamEvent.OutputItemAdded(
                                outputIndex = 0,
                                item = item,
                            ),
                        )
                        streamOpened.complete(Unit)
                        releaseStream.await()
                        emit(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = item.copy(
                                    content = listOf(ContentItem.OutputText("completed in background")),
                                ),
                            ),
                        )
                        emit(
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "background-response",
                                    endTurn = true,
                                ),
                            ),
                        )
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            val sessionIndex = initializedRoot(repository, "Background")
            val store = testSessionViewModelRegistry(repository, this)
            val model = store.open(sessionIndex)
            val frontendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val keepFrontendCallerActive = CompletableDeferred<Unit>()
            try {
                val submission = frontendScope.async {
                    model.rootAgent.submit(
                        listOf(ContentItem.InputText("keep running after renderer switch")),
                    )
                    keepFrontendCallerActive.await()
                }
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { streamOpened.await() }
                }
                assertSame(model, store.open(sessionIndex))
                assertSame(model.rootAgent, store.open(sessionIndex).rootAgent)

                frontendScope.cancel()
                submission.join()
                yield()

                assertTrue(model.rootAgent.execution.value.running)
                assertTrue(model.summary.value.rootRunning)

                releaseStream.complete(Unit)
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        model.rootAgent.execution.first { execution -> !execution.running }
                    }
                }
                assertEquals(AgentExecutionPhase.AssistantMessage, model.rootAgent.execution.value.phase)
            } finally {
                frontendScope.cancel()
                keepFrontendCallerActive.complete(Unit)
                releaseStream.complete(Unit)
                withContext(NonCancellable) {
                    store.shutdown()
                    repository.cancelAndJoin()
                }
            }
        }
    }

    test("explicit Agent cancellation stops the ViewModel-owned turn") {
        coroutineScope {
            val streamOpened = CompletableDeferred<Unit>()
            val releaseStream = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse {
                    kotlinx.coroutines.flow.flow {
                        streamOpened.complete(Unit)
                        releaseStream.await()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            val sessionIndex = initializedRoot(repository, "Explicit cancellation")
            val store = testSessionViewModelRegistry(repository, this)
            val model = store.open(sessionIndex)
            try {
                model.rootAgent.submit(
                    listOf(ContentItem.InputText("wait until explicitly cancelled")),
                )
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { streamOpened.await() }
                }
                assertTrue(model.rootAgent.execution.value.running)
                assertEquals(AgentLifecycleState.Open, model.rootAgent.lifecycle.value)

                model.rootAgent.cancel()

                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        model.rootAgent.execution.first { execution -> !execution.running }
                    }
                }
                assertTrue(!model.rootAgent.execution.value.running)
                assertEquals(AgentLifecycleState.Open, model.rootAgent.lifecycle.value)
            } finally {
                releaseStream.complete(Unit)
                withContext(NonCancellable) {
                    store.shutdown()
                    repository.cancelAndJoin()
                }
            }
        }
    }

    test("settings remain writable while the Agent is running") {
        coroutineScope {
            val streamOpened = CompletableDeferred<Unit>()
            val releaseStream = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse {
                    kotlinx.coroutines.flow.flow {
                        streamOpened.complete(Unit)
                        releaseStream.await()
                        emit(
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "concurrent-settings-response",
                                    endTurn = true,
                                ),
                            ),
                        )
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            val sessionIndex = initializedRoot(repository, "Concurrent settings")
            val store = testSessionViewModelRegistry(repository, this)
            val model = store.open(sessionIndex)
            val updatedModel = OpenAiModelId("next-turn-model")
            try {
                model.rootAgent.submit(
                    listOf(ContentItem.InputText("keep running while settings change")),
                )
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { streamOpened.await() }
                }
                assertTrue(model.rootAgent.execution.value.running)

                model.rootAgent.updateModel(updatedModel)

                assertTrue(model.rootAgent.execution.value.running)
                assertEquals(updatedModel, model.rootAgent.settings.value.model)
                assertEquals(
                    updatedModel,
                    repository.open(sessionIndex).storage.settings[
                        repository.open(sessionIndex).runtime.latestIndex.value
                    ].model,
                )
            } finally {
                releaseStream.complete(Unit)
                withContext(NonCancellable) {
                    withTimeout(10.seconds) {
                        model.rootAgent.execution.first { execution -> !execution.running }
                    }
                    store.shutdown()
                    repository.cancelAndJoin()
                }
            }
        }
    }

    test("releasing a Session closes its Agent ViewModel and cancels its owned turn") {
        coroutineScope {
            val streamOpened = CompletableDeferred<Unit>()
            val releaseStream = CompletableDeferred<Unit>()
            val streamCancelled = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse {
                    kotlinx.coroutines.flow.flow {
                        streamOpened.complete(Unit)
                        try {
                            releaseStream.await()
                        } finally {
                            streamCancelled.complete(Unit)
                        }
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            val sessionIndex = initializedRoot(repository, "Session release")
            val store = testSessionViewModelRegistry(repository, this)
            val model = store.open(sessionIndex)
            try {
                model.rootAgent.submit(
                    listOf(ContentItem.InputText("run until the Session closes")),
                )
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { streamOpened.await() }
                }
                assertTrue(model.rootAgent.execution.value.running)

                store.release(sessionIndex)

                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { streamCancelled.await() }
                    withTimeout(10.seconds) {
                        repository.open(sessionIndex).runtime.runningTurn.first { turn -> turn == null }
                    }
                }
                assertEquals(PersistedSessionLifecycleState.Closed, model.lifecycle.value)
                assertEquals(AgentLifecycleState.Closed, model.rootAgent.lifecycle.value)
            } finally {
                releaseStream.complete(Unit)
                withContext(NonCancellable) {
                    store.shutdown()
                    repository.cancelAndJoin()
                }
            }
        }
    }

    test("store reuses and releases exact persisted session handles") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = initializedRoot(repository, "Reusable")
            val store = testSessionViewModelRegistry(repository, this)
            try {
                val first = store.open(sessionIndex)
                assertSame(first, store.open(sessionIndex))

                store.release(sessionIndex)
                val reopened = store.open(sessionIndex)
                assertNotSame(first, reopened)
                assertEquals(sessionIndex, reopened.sessionIndex)
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun initializedRoot(
    repository: InMemoryKodexSessionRepository,
    name: String,
): Int = repository.create().also { sessionIndex ->
    repository.open(sessionIndex).runtime.modify { storage ->
        storage.initialize(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = name,
            ),
        )
    }
}

private suspend fun createInitializedChild(
    parent: KodexAgentSession,
    name: String,
): KodexAgentSession = initializeAgent(
    parent.subagents.open(parent.subagents.create()),
    name,
)

private suspend fun initializeAgent(
    session: KodexAgentSession,
    name: String,
): KodexAgentSession = session.also { agent ->
    agent.runtime.modify { storage ->
        storage.initialize(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = name,
            ),
        )
    }
}

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(listOf(ContentItem.InputText(text)))
