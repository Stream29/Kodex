package io.github.stream29.kodex.agentsession.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun settings(name: String = ""): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = name)

private fun userMessage(): StableUserMessage =
    StableUserMessage(
        content = listOf(ContentItem.InputText("copied")),
    )

private suspend fun KodexSessionRepository.createInitialized(
    settings: KodexAgentSettings,
): Int {
    val index = create()
    open(index).runtime.modify { storage ->
        storage.initialize(settings.copy(threadName = settings.threadName.ifEmpty { "Session $index" }))
    }
    return index
}

val inMemoryKodexSessionRepositoryTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("creates an uninitialized root storage") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val index = repository.create()
            val session = repository.open(index)

            assertEquals(-1, session.storage.latestIndex())
            session.runtime.modify { storage -> storage.initialize(settings("root")) }
            assertEquals(0, session.storage.latestIndex())
            assertEquals(0L, session.storage.tokenCount[0])
        }

        test("creates zero-based root entries") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val first = repository.createInitialized(settings())
            val second = repository.createInitialized(settings("Named"))
            val firstLastActivityAt = Instant.parse("2026-07-31T10:00:00Z")
            val secondLastActivityAt = Instant.parse("2026-07-31T10:05:00Z")
            repository.open(first).storage.timestamp[2] = firstLastActivityAt
            repository.open(second).storage.timestamp[2] = secondLastActivityAt

            assertEquals(0, first)
            assertEquals(1, second)
            assertEquals("Session 0", repository.open(first).storage.settings[0].threadName)
            assertEquals(listOf(first, second), repository.list())
            assertEquals(
                listOf(
                    Triple(first, "Session 0", firstLastActivityAt),
                    Triple(second, "Named", secondLastActivityAt),
                ),
                repository.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.threadName, entry.lastActivityAt)
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
            assertEquals(listOf("archived", "active"), allEntries.map { it.threadName })
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

            assertFailsWith<IllegalStateException> { root.storage.settings.latestIndex() }
            assertEquals(0, repository.createInitialized(settings()))
        }

        test("fork is a downstream operation and does not copy descendants") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("Source"))
            val source = repository.open(sourceIndex)
            source.runtime.injectHistory(listOf(userMessage()))

            val targetIndex = repository.createFork(sourceIndex)
            val target = repository.open(targetIndex)
            val latest = target.storage.latestIndex()
            target.runtime.updateSettings(
                target.storage.settings[latest].copy(threadName = "[fork] Source"),
            )

            assertEquals(listOf(1), target.storage.index.indexesIn(0..latest))
            assertEquals(userMessage(), target.storage.index[1])
            assertEquals("[fork] Source", target.storage.settings[2].threadName)
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
                createResponse { _, _, _, _ ->
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

        test("runtime leaves host-owned pending calls in state") {
            val client = mockOpenAiClient {
                createResponse { _, _, _, _ ->
                    flowOf(
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
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client),
            )
            val root = repository.open(repository.createInitialized(settings("root")))
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Ask the host.")))

            root.runtime.resume()

            val pending = assertIs<KodexAgentStateValue.ToolPending>(root.runtime.state.value)
            assertEquals(listOf("call_host_only"), pending.events.map { event -> event.callId })
        }

        test("cancelling a turn fails its persisted pending tool calls") {
            val pending = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _, _, _, _ ->
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

            val failure = assertIs<StableTextToolEvent>(root.storage.work[4])
            assertEquals("user interrupt", failure.result)
            assertEquals(false, failure.success)
            assertEquals(emptyList(), root.storage.unstable[4])
            assertEquals(KodexAgentStateValue.ToolCompleted, root.runtime.state.value)
            assertNull(root.runtime.runningTurn.value)
        }

    }
}
