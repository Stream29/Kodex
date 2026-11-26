package io.github.stream29.kodex.agentsession.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin as cancelJobAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun settings(name: String = ""): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"), name = name)

private fun userMessage(): StableUserMessage =
    StableUserMessage(
        content = listOf(ContentItem.InputText("copied")),
    )

private suspend fun KodexSessionRepository.createInitialized(
    settings: KodexAgentSettings,
): Int {
    val index = create()
    open(index).runtime.modify { storage ->
        storage.initialize(settings.copy(name = settings.name.ifEmpty { "Session $index" }))
    }
    return index
}

val inMemoryKodexSessionRepositoryTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("sparse fork keeps only the retained checkpoint window and reverts cleanly") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceId = repository.createInitialized(settings("source"))
            val source = repository.open(sourceId)
            val checkpoint = StableContextCompaction(encryptedContent = "compact")
            val (old, packedAt) = source.runtime.modify {
                val old = it.commit(StableAssistantMessage(listOf(ContentItem.OutputText("outside"))))
                it.commit(UnstableSnapshot(emptyList()))
                old to it.commit(checkpoint)
            }
            val fork = source.runtime.modify { checkNotNull(repository.createAgentFork(sourceId, source, null)) }
            assertEquals("source", fork.boundarySettings.name)
            val target = repository.open(fork.sessionId)
            assertEquals(packedAt, target.storage.latestIndex())
            assertNull(target.storage.getCommitExact(old))
            assertEquals(checkpoint, target.storage.getCommitExact(packedAt, AgentCommitType.IndexTypes)?.commit)
            assertEquals(emptyList(), target.storage.getUnstableAtOrNull(packedAt))
            assertEquals(KodexAgentStateValue.Empty, target.runtime.state.value)
            val next = target.runtime.appendUserMessage(listOf(ContentItem.InputText("fork reply")))
            assertEquals(packedAt + 1, next)
            assertEquals(KodexAgentStateValue.UserMessage, target.runtime.state.value)
            target.runtime.modify { it.revert(packedAt + 1) }
            repository.closeSession(fork.sessionId)
            val reopened = repository.open(fork.sessionId)
            assertEquals(KodexAgentStateValue.Empty, reopened.runtime.state.value)
            reopened.runtime.modify { it.revert(packedAt) }
            assertEquals(KodexAgentStateValue.Empty, reopened.runtime.state.value)
            assertEquals(0, reopened.storage.latestIndex())
            assertEquals(KodexAgentStateValue.AssistantMessage, source.runtime.state.value)
            assertNotNull(source.storage.getCommitExact(old))
        }

        test("catalog samples residency and running without activating entries") {
            val entered = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _ ->
                    flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            try {
                val index = repository.create()
                val unopened = repository.listEntries().single()
                assertFalse(unopened.isActive)
                assertFalse(unopened.running)
                assertFalse(repository.getEntry(index).isActive)

                val session = repository.open(index)
                session.runtime.modify { it.initialize(settings("root")) }
                assertSame(session, repository.open(index))
                val idle = repository.getEntry(index)
                assertTrue(idle.isActive)
                assertFalse(idle.running)
                assertFalse(unopened.isActive)

                idle.archive()
                session.runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))
                val turn = async { session.runtime.resume() }
                try {
                    entered.await()
                    assertEquals(emptyList(), repository.listEntries(includeArchived = false))
                    val running = repository.listEntries(includeArchived = true).single()
                    assertTrue(running.archived)
                    assertTrue(running.isActive)
                    assertTrue(running.running)
                    assertTrue(repository.getEntry(index).running)
                    assertFalse(idle.running)
                    turn.cancelJobAndJoin()
                    assertTrue(running.running)
                    assertFalse(repository.getEntry(index).running)
                    assertTrue(repository.getEntry(index).isActive)
                } finally {
                    turn.cancelJobAndJoin()
                }

                session.cancelAndJoin()
                val closed = repository.listEntries(includeArchived = true).single()
                assertFalse(closed.isActive)
                assertFalse(closed.running)
                assertFalse(repository.getEntry(index).isActive)
                val reopened = repository.open(index)
                assertTrue(reopened !== session)
                assertSame(reopened, repository.open(index))
                assertTrue(repository.getEntry(index).isActive)
                assertFalse(repository.getEntry(index).running)
            } finally {
                repository.cancelAndJoin()
            }
        }

        test("creates an uninitialized root storage") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val session = repository.open(index)

            assertEquals(index, session.storage.agentId)
            assertEquals(index, session.runtime.agentId)
            assertEquals(-1, session.storage.latestIndex())
            session.runtime.modify { storage -> storage.initialize(settings("root")) }
            assertEquals(0, session.storage.latestIndex())
            assertEquals(0L, session.storage.getLlmRequestMetaAtOrNull(0)!!.totalTokens)
        }

        test("creates zero-based root entries") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val first = repository.createInitialized(settings())
            val second = repository.createInitialized(settings("Named"))
            val firstLastActivityAt = Instant.parse("2026-07-31T10:00:00Z")
            val secondLastActivityAt = Instant.parse("2026-07-31T10:05:00Z")
            repository.open(first).storage.commit(listOf(LlmRequestMeta.Initialization(0L)), firstLastActivityAt)
            repository.open(second).storage.commit(listOf(LlmRequestMeta.Initialization(0L)), secondLastActivityAt)

            assertEquals(0, first)
            assertEquals(1, second)
            assertEquals("Session 0", repository.open(first).storage.getSettingsAt(0).name)
            assertEquals(listOf(first, second), repository.list())
            assertEquals(
                listOf(
                    Triple(first, "Session 0", firstLastActivityAt),
                    Triple(second, "Named", secondLastActivityAt),
                ),
                repository.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.name, entry.lastActivityAt)
                },
            )
        }

        test("archives and unarchives root entries without affecting index inventory") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val archivedIndex = repository.createInitialized(settings("archived"))
            val activeIndex = repository.createInitialized(settings("active"))
            val archivedEntry = repository.getEntry(archivedIndex)

            archivedEntry.archive()
            archivedEntry.archive()

            assertEquals(listOf(activeIndex), repository.listEntries(includeArchived = false).map { it.entryIndex })
            val allEntries = repository.listEntries(includeArchived = true)
            assertEquals(listOf(archivedIndex, activeIndex), allEntries.map { it.entryIndex })
            assertEquals(listOf("archived", "active"), allEntries.map { it.name })
            assertEquals(listOf(true, false), allEntries.map { it.archived })
            assertEquals(listOf(archivedIndex, activeIndex), repository.list())

            archivedEntry.unarchive()
            archivedEntry.unarchive()

            assertEquals(
                listOf(archivedIndex, activeIndex),
                repository.listEntries(includeArchived = false).map { it.entryIndex },
            )
        }

        test("archived roots remain openable, forkable, deletable, and reusable") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("source"))
            val source = repository.open(sourceIndex)
            repository.listEntries()
                .single { entry -> entry.entryIndex == sourceIndex }
                .archive()

            assertSame(source, repository.open(sourceIndex))

            val forkIndex = repository.createFork(sourceIndex)
            assertEquals(
                false,
                repository.listEntries(includeArchived = true)
                    .single { entry -> entry.entryIndex == forkIndex }
                    .archived,
            )

            repository.delete(sourceIndex)
            assertEquals(sourceIndex, repository.create())
            assertEquals(
                false,
                repository.listEntries(includeArchived = true)
                    .single { entry -> entry.entryIndex == sourceIndex }
                    .archived,
            )
        }

        test("delete invalidates cached nodes and releases the numeric slot") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.createInitialized(settings())
            val root = repository.open(index)

            repository.delete(index)

            assertFailsWith<IllegalStateException> { root.storage.latestIndex() }
            assertEquals(0, repository.createInitialized(settings()))
        }

        test("fork is a downstream operation and does not copy descendants") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("Source"))
            val source = repository.open(sourceIndex)
            source.runtime.modify { it.commit(userMessage()) }

            val targetIndex = repository.createFork(sourceIndex)
            val target = repository.open(targetIndex)
            val latest = target.storage.latestIndex()
            target.runtime.updateSettings(
                target.storage.getSettingsAt(latest).copy(name = "[fork] Source"),
            )

            assertEquals(listOf(1), target.storage.getFirstCommitsIn(0..latest, AgentCommitType.IndexTypes).map { it.index })
            assertEquals(userMessage(), target.storage.getCommitAtOrBefore(1, AgentCommitType.IndexTypes)?.commit)
            assertEquals("[fork] Source", target.storage.getSettingsAt(2).name)
        }

        test("owns each runtime for the complete Agent session lifecycle") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.createInitialized(settings("root")))

            assertSame(root.storage, root.runtime.storage)

            root.cancelAndJoin()

            assertFalse(root.runtime.coroutineContext[Job]?.isActive ?: true)
        }

        test("exposes a Unified Exec client for the root Agent runtime") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.createInitialized(settings("root")))

            assertNotNull(root.runtime.unifiedExecToolClient)
        }

        test("runtime rejects concurrent resume operations") {
            val entered = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _ ->
                    flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                root.runtime.resume()
            }
            entered.await()
            assertSame(first, root.runtime.runningTurn.value)

            assertFailsWith<ConcurrentAgentRuntimeResumeException> {
                root.runtime.resume()
            }
            assertSame(first, root.runtime.runningTurn.value)

            first.cancelJobAndJoin()
            assertNull(root.runtime.runningTurn.value)
        }

        test("runtime fails unsupported pending calls and continues turn") {
            var calls = 0
            val client = mockOpenAiClient {
                createResponse { _ ->
                    when (++calls) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = "host_only",
                                    arguments = "{}",
                                    callId = "call_host_only",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                        else -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("Recovered")),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Ask the host.")))

            root.runtime.resume()

            assertEquals(2, calls)
            assertEquals(KodexAgentStateValue.AssistantMessage, root.runtime.state.value)
            val failed = assertIs<StableTextTool>(
                root.storage.getCommitAtOrBefore(root.storage.latestIndex(), AgentCommitType.WorkTypes)?.commit,
            )
            assertEquals("call_host_only", failed.callId)
            assertEquals(false, failed.success)
            assertTrue("unsupported tool call" in failed.result)
        }

        test("cancelling a turn fails its persisted pending tool calls") {
            val pending = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _ ->
                    flow {
                        emit(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = "unhandled",
                                    arguments = "{}",
                                    callId = "call_1",
                                ),
                            ),
                        )
                        pending.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Start a turn.")))

            val turn = async(start = CoroutineStart.UNDISPATCHED) {
                root.runtime.resume()
            }
            pending.await()
            turn.cancelJobAndJoin()

            val failure = assertIs<StableTextTool>(root.storage.getCommitAtOrBefore(3, AgentCommitType.WorkTypes)?.commit)
            assertEquals("user interrupt", failure.result)
            assertEquals(false, failure.success)
            assertEquals(emptyList(), root.storage.getUnstableAtOrNull(3))
            assertEquals(KodexAgentStateValue.ToolCompleted, root.runtime.state.value)
            assertNull(root.runtime.runningTurn.value)
        }

        test("idle TTL eviction drains session without self-deadlocking and allows clean reopen") {
            withContext(Dispatchers.Default) {
                val repository = InMemoryKodexSessionRepository(
                    dependencies = testKodexAgentDependencies(),
                    sessionTtl = 40.milliseconds,
                )
                try {
                    val index = repository.createInitialized(settings("ttl-memory"))
                    val first = repository.open(index)
                    assertTrue(index in repository.openSessions.value)
                    withTimeout(5.seconds) {
                        while (index in repository.openSessions.value) {
                            delay(10.milliseconds)
                        }
                        first.coroutineContext[Job]?.join()
                    }
                    assertFalse(first.coroutineContext[Job]?.isActive ?: true)
                    val reopened = repository.open(index)
                    assertNotSame(first, reopened)
                    assertEquals("ttl-memory", reopened.storage.getSettingsAt(0).name)
                } finally {
                    repository.cancelAndJoin()
                }
            }
        }

    }
}
