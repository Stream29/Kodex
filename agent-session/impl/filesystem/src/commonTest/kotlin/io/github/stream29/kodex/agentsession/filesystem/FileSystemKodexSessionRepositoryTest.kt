package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.io.files.Path
import kotlinx.io.IOException
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

private suspend fun temporaryRepositoryRoot(): Path =
    Path(SystemTemporaryDirectory, "kodex-session-${Random.nextLong()}").also { root ->
        SystemCoroutineFileSystem.createDirectories(root)
    }

private fun settings(
    name: String = "",
    cwd: Path = Path("."),
): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"), cwd = cwd, threadName = name)

private fun userMessage(text: String): StableUserMessage =
    StableUserMessage(
        content = listOf(ContentItem.InputText(text)),
    )

private fun pendingTool(callId: String): PendingToolEvent =
    PendingCustomToolEvent(
        callId = callId,
        name = "tool-$callId",
        input = "input-$callId",
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

val fileSystemKodexSessionRepositoryTest by testSuite {
    testFixture { temporaryRepositoryRoot() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        for (kind in listOf("io", "cancellation")) {
            test("failed settings rescan after published $kind rejects stale runtime CAS until reopen") { root ->
                val owner = SupervisorJob(coroutineContext[Job])
                val scope = CoroutineScope(coroutineContext + owner)
                val fileSystem = SettingsPublicationRescanFaultFileSystem()
                val repository = try {
                    scope.FileSystemKodexSessionRepository(root, testKodexAgentDependencies(), fileSystem)
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { owner.cancelAndJoin() }
                    throw failure
                }
                try {
                    val index = repository.createInitialized(settings("original"))
                    val healthyIndex = repository.createInitialized(settings("healthy"))
                    val session = repository.open(index)
                    val healthy = repository.open(healthyIndex)
                    val timeline = assertIs<ObservableKodexAgentStorage>(session.storage).settings
                    val original = timeline[0]
                    val published = original.copy(threadName = "published")
                    val primary = if (kind == "io") IOException("published settings cleanup")
                    else CancellationException("published settings cleanup cancelled")
                    val secondary = IOException("settings numbered record rescan")
                    val nonce = timeline.cacheNonce.value
                    val settingsDirectory = Path(root, "sessions/$index/settings")
                    val durableRecord = Path(settingsDirectory, "1.json")
                    fileSystem.arm(settingsDirectory, primary, secondary)

                    // Catch inside the operation, including injected cancellation,
                    // without cancelling the borrowed repository/backend owner.
                    val observed = runCatching {
                        session.runtime.compareAndSetSettings(original, published)
                    }.exceptionOrNull()
                    assertSame(primary, observed)
                    assertTrue(primary.suppressedExceptions.any { it === secondary })
                    val durableBytes = SystemCoroutineFileSystem.readBytes(durableRecord)
                    assertEquals(0, timeline.latestIndex.value)
                    assertEquals(nonce, timeline.cacheNonce.value)
                    assertEquals(0, session.runtime.latestIndex.value)
                    assertTrue(owner.isActive)
                    assertTrue(repository.coroutineContext[Job]!!.isActive)
                    assertTrue(session.coroutineContext[Job]!!.isActive)

                    // Faults are one-shot and now absent: neither stale nor durable
                    // settings may be used by this uncertain original runtime.
                    val scanCount = fileSystem.targetListCount
                    assertFailsWith<IllegalStateException> {
                        session.runtime.compareAndSetSettings(original, original.copy(threadName = "replacement"))
                    }
                    assertFailsWith<IllegalStateException> {
                        session.runtime.compareAndSetSettings(original, original)
                    }
                    assertFailsWith<IllegalStateException> {
                        session.runtime.compareAndSetSettings(published, published.copy(threadName = "next"))
                    }
                    assertFailsWith<IllegalStateException> { timeline[0] }
                    assertFailsWith<IllegalStateException> { session.storage.settings[1] = original }
                    assertFailsWith<IllegalStateException> { session.storage.settings.revert(1) }
                    assertEquals(scanCount, fileSystem.targetListCount, "No implicit rescan/retry is admitted.")
                    assertContentEquals(durableBytes, SystemCoroutineFileSystem.readBytes(durableRecord))
                    assertFalse(SystemCoroutineFileSystem.exists(Path(settingsDirectory, "2.json")))
                    assertFalse(SystemCoroutineFileSystem.exists(Path(root, "sessions/$index/timestamp/1.json")))
                    assertEquals(
                        setOf("0.json", "1.json"),
                        SystemCoroutineFileSystem.list(settingsDirectory)
                            .map(Path::name).filter { it.firstOrNull()?.isDigit() == true }.toSet(),
                    )

                    val healthyOriginal = healthy.storage.settings[healthy.runtime.latestIndex.value]
                    assertTrue(healthy.runtime.compareAndSetSettings(
                        healthyOriginal, healthyOriginal.copy(threadName = "still healthy"),
                    ))
                    assertEquals("still healthy", healthy.storage.settings[healthy.runtime.latestIndex.value].threadName)
                    assertTrue(owner.isActive)
                    assertSame(session, repository.open(index))

                    session.coroutineContext[Job]!!.cancelAndJoin()
                    val reopened = repository.open(index)
                    assertTrue(reopened !== session)
                    assertEquals(1, reopened.runtime.latestIndex.value)
                    assertEquals(published, reopened.storage.settings[1])
                    assertFalse(reopened.runtime.compareAndSetSettings(original, published))
                    val next = published.copy(threadName = "after reopen")
                    assertTrue(reopened.runtime.compareAndSetSettings(published, next))
                    assertEquals(2, reopened.runtime.latestIndex.value)
                    assertEquals(next, reopened.storage.settings.getExact(2))
                    assertContentEquals(durableBytes, SystemCoroutineFileSystem.readBytes(durableRecord))
                    assertTrue(owner.isActive)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            repository.closeAndJoin()
                        } finally {
                            owner.cancelAndJoin()
                        }
                    }
                }
            }
        }
        test("failed initial scan never attaches an unreturned repository owner") { root ->
            val owner = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + owner)
            val failure = IOException("initial entry scan")
            val failingFileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun list(directory: Path): Collection<Path> {
                    if (directory == Path(root, "sessions")) throw failure
                    return SystemCoroutineFileSystem.list(directory)
                }
            }
            try {
                repeat(2) {
                    assertSame(failure, assertFailsWith<IOException> {
                        scope.FileSystemKodexSessionRepository(
                            root, testKodexAgentDependencies(), failingFileSystem,
                        )
                    })
                    assertTrue(owner.children.none(), "Failed scanning must not retain a child owner.")
                }
                val repository = scope.FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
                assertEquals(emptyList(), repository.list())
                repository.coroutineContext[Job]!!.cancelAndJoin()
                assertTrue(owner.children.none())
            } finally {
                owner.cancelAndJoin()
            }
        }
        test("settings CAS appends through cached file timelines and survives reopen") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            try {
                val index = repository.createInitialized(settings("original"))
                val session = repository.open(index)
                val view = assertIs<ObservableKodexAgentStorage>(session.runtime.storage)
                val initialIndex = session.runtime.latestIndex.value
                val initial = view.settings.getExact(initialIndex)!!
                val initialTimestamp = view.timestamp.getExact(initialIndex)
                val settingsNonce = view.settings.cacheNonce.value
                val timestampNonce = view.timestamp.cacheNonce.value
                val update = initial.copy(threadName = "updated")

                assertFalse(session.runtime.compareAndSetSettings(initial.copy(threadName = "old"), update))
                assertTrue(session.runtime.compareAndSetSettings(initial.copy(), initial.copy()))
                assertEquals(initialIndex, view.settings.latestIndex.value)
                assertEquals(initialIndex, view.timestamp.latestIndex.value)
                assertEquals(listOf(initialIndex), view.settings.indexesIn(0..initialIndex + 1))
                assertTrue(session.runtime.compareAndSetSettings(initial, update))

                val committed = initialIndex + 1
                assertEquals(committed, session.runtime.latestIndex.value)
                assertEquals(committed, view.settings.latestIndex.value)
                assertEquals(committed, view.timestamp.latestIndex.value)
                assertEquals(settingsNonce, view.settings.cacheNonce.value)
                assertEquals(timestampNonce, view.timestamp.cacheNonce.value)
                assertEquals(initial, view.settings.getExact(initialIndex))
                assertEquals(initialTimestamp, view.timestamp.getExact(initialIndex))
                assertEquals(update, view.settings.getExact(committed))
                session.coroutineContext[Job]!!.cancelAndJoin()

                val reopened = repository.open(index)
                assertEquals(committed, reopened.runtime.latestIndex.value)
                assertEquals(initial, reopened.runtime.storage.settings.getExact(initialIndex))
                assertEquals(update, reopened.runtime.storage.settings.getExact(committed))
                assertEquals(listOf(initialIndex, committed), reopened.runtime.storage.timestamp.indexesIn(0..committed))
            } finally {
                repository.closeAndJoin()
            }
        }

        test("catalog samples residency and running without activating entries") { root ->
            val entered = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _ ->
                    flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies(client))
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
                    turn.cancelAndJoin()
                    assertTrue(running.running)
                    assertFalse(repository.getEntry(index).running)
                    assertTrue(repository.getEntry(index).isActive)
                } finally {
                    turn.cancelAndJoin()
                }

                session.coroutineContext[Job]!!.cancelAndJoin()
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
                repository.closeAndJoin()
            }
        }

        test("cache key survives reopen and default fork uses its own thread") { root ->
            for (override in listOf(null, "explicit")) {
                val requests = mutableListOf<ResponsesApiRequest>()
                val dependencies = testKodexAgentDependencies(mockOpenAiClient {
                    createResponse { request ->
                        requests += request
                        flowOf(ResponsesStreamEvent.Completed(Response(id = "test", endTurn = true)))
                    }
                })
                val repository = FileSystemKodexSessionRepository(root, dependencies)
                val index: Int
                try {
                    index = repository.createInitialized(settings("Cache").copy(promptCacheKey = override))
                    val session = repository.open(index)
                    session.runtime.appendUserMessage(listOf(ContentItem.InputText("First.")))
                    session.runtime.requestResponseApi()
                } finally {
                    repository.closeAndJoin()
                }
                val reopened = FileSystemKodexSessionRepository(root, dependencies)
                try {
                    val source = reopened.open(index)
                    source.runtime.appendUserMessage(listOf(ContentItem.InputText("Again.")))
                    source.runtime.requestResponseApi()
                    val fork = reopened.open(reopened.createFork(index))
                    fork.runtime.appendUserMessage(listOf(ContentItem.InputText("Fork.")))
                    fork.runtime.requestResponseApi()
                    assertEquals(3, requests.size)
                    assertEquals(requests[0].promptCacheKey, requests[1].promptCacheKey)
                    for (request in requests) {
                        assertEquals(override ?: request.clientMetadata!!.threadId, request.promptCacheKey)
                    }
                    if (override == null) assertNotEquals(requests[0].promptCacheKey, requests[2].promptCacheKey)
                    assertEquals(override, fork.storage.settings[fork.storage.latestIndex()].promptCacheKey)
                } finally {
                    reopened.closeAndJoin()
                }
            }
        }

        test("creates an uninitialized root storage") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = repository.create()
            val session = repository.open(index)

            assertEquals(-1, session.storage.latestIndex())
            session.runtime.modify { storage -> storage.initialize(settings("root")) }
            assertEquals(0, session.storage.latestIndex())
            assertEquals(0L, session.storage.tokenCount[0].totalTokens)
            repository.closeAndJoin()
        }

        test("session storage exposes the same six cached timelines through its read-only view") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            try {
                val session = repository.open(repository.create())
                val writable = session.storage
                val view = assertIs<ObservableKodexAgentStorage>(writable)
                assertSame(writable, view)
                assertEquals(writable.uri, view.uri)
                val timelines = listOf(
                    view.index to writable.index,
                    view.work to writable.work,
                    view.settings to writable.settings,
                    view.timestamp to writable.timestamp,
                    view.tokenCount to writable.tokenCount,
                    view.unstable to writable.unstable,
                )
                for ((observed, original) in timelines) {
                    assertSame(original, observed)
                    assertEquals(-1, observed.latestIndex.value)
                }

                session.runtime.modify { it.initialize(settings("Observed")) }
                for ((observed, original) in timelines) {
                    assertEquals(original.latestIndex(), observed.latestIndex.value)
                    assertEquals(original.getExact(0), observed.getExact(0))
                }
                assertEquals("Observed", view.settings[0].threadName)
                val snapshot = TokenCountSnapshot(TokenCountKind.Response, 120)
                val tokenNonce = view.tokenCount.cacheNonce.value
                writable.tokenCount[2] = snapshot
                assertEquals(snapshot, view.tokenCount.getExact(2))
                assertEquals(2, view.tokenCount.latestIndex.value)
                assertEquals(tokenNonce, view.tokenCount.cacheNonce.value)
                writable.tokenCount.revert(2)
                assertEquals(null, view.tokenCount.getExact(2))
                assertEquals(0, view.tokenCount.latestIndex.value)
                assertNotEquals(tokenNonce, view.tokenCount.cacheNonce.value)
                val timestamp = Instant.parse("2026-09-18T00:00:00Z")
                writable.timestamp[2] = timestamp
                assertEquals(2, view.timestamp.latestIndex.value)
                assertEquals(timestamp, view.timestamp.getExact(2))
                val nonce = view.timestamp.cacheNonce.value
                writable.timestamp.revert(2)
                assertEquals(0, view.timestamp.latestIndex.value)
                assertNotEquals(nonce, view.timestamp.cacheNonce.value)

                session.coroutineContext[Job]?.cancelAndJoin()
                assertFailsWith<IllegalStateException> { view.timestamp }
            } finally {
                repository.closeAndJoin()
            }
        }

        test("persists canonical root layout and lightweight entries") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val cwd = Path(root, "workspace")
            val index = repository.createInitialized(settings(cwd = cwd))
            val session = repository.open(index)
            val uri = session.storage.uri
            val timestamp = Instant.parse("2026-07-22T00:00:00Z")
            val stableEvent = StableAssistantMessage(
                listOf(ContentItem.OutputText("persisted clean event")),
            )
            val pendingEvents: List<UnstableCleanEvent> = listOf(
                pendingTool("call-persisted"),
                PendingServerToolSearch(
                    ResponseItem.ServerToolSearchCall(
                        id = ResponseItemId("server-tool-search"),
                        arguments = buildJsonObject {
                            put("query", "connected drive tools")
                        },
                    ),
                ),
            )
            session.storage.timestamp[2] = timestamp
            session.storage.index[2] = stableEvent
            session.storage.unstable[2] = pendingEvents
            val directory = Path(root, "sessions/0")
            assertEquals(
                setOf(
                    "index",
                    "settings",
                    "timestamp",
                    "token-count",
                    "work",
                    "unstable",
                    "lock.json",
                ),
                SystemCoroutineFileSystem.list(directory)
                    .map(Path::name)
                    .filterNot { name -> name.startsWith(".") }
                    .toSet(),
            )
            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "manifest.json")))
            assertEquals(listOf(index), repository.list())
            repository.closeAndJoin()

            val reopened = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val reopenedSession = reopened.open(index)
            assertEquals(listOf(index), reopened.entries.value)
            assertEquals(uri, reopenedSession.storage.uri)
            assertEquals(cwd, reopenedSession.storage.settings[0].cwd)
            assertEquals(stableEvent, reopenedSession.storage.index[2])
            assertEquals(pendingEvents, reopenedSession.storage.unstable[2])
            reopened.closeAndJoin()
        }

        test("allocates the next slot when an earlier root directory already exists") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())

            val first = repository.createInitialized(settings("first"))
            val second = repository.createInitialized(settings("second"))
            val firstLastActivityAt = Instant.parse("2026-07-31T10:00:00Z")
            val secondLastActivityAt = Instant.parse("2026-07-31T10:05:00Z")
            repository.open(first).storage.timestamp[2] = firstLastActivityAt
            repository.open(second).storage.timestamp[2] = secondLastActivityAt

            assertEquals(0, first)
            assertEquals(1, second)
            assertEquals(
                listOf(
                    Triple(first, "first", firstLastActivityAt),
                    Triple(second, "second", secondLastActivityAt),
                ),
                repository.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.threadName, entry.lastActivityAt)
                },
            )
            repository.closeAndJoin()
        }

        test("lists warm session entries without enumerating timelines") { root ->
            val fileSystem = CountingListFileSystem()
            val repository = FileSystemKodexSessionRepository(
                root = root,
                dependencies = testKodexAgentDependencies(),
                fileSystem = fileSystem,
            )
            val index = repository.createInitialized(settings("indexed"))
            val lastActivityAt = Instant.parse("2026-08-14T12:00:00Z")
            repository.open(index).storage.timestamp[2] = lastActivityAt
            fileSystem.reset()

            assertEquals(
                listOf(Triple(index, "indexed", lastActivityAt)),
                repository.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.threadName, entry.lastActivityAt)
                },
            )
            assertEquals(0, fileSystem.listCalls)
            repository.closeAndJoin()
        }

        test("menu timestamps are independent exact reads without timeline enumeration") { root ->
            val fileSystem = CountingListFileSystem()
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies(), fileSystem)
            try {
                val empty = repository.create()
                assertEquals(null, repository.readCreatedAt(empty))
                assertEquals(null, repository.readUpdatedAt(empty))
                val index = repository.createInitialized(settings("timestamps"))
                val storage = repository.open(index).storage
                val created = storage.timestamp.getExact(0)
                val updated = Instant.parse("2020-01-02T03:04:05Z")
                storage.timestamp[2] = updated
                fileSystem.reset()
                assertEquals(created, repository.readCreatedAt(index))
                assertEquals(updated, repository.readUpdatedAt(index))
                assertEquals(0, fileSystem.listCalls)

                val zero = Path(root, "sessions/$index/timestamp/0.json")
                SystemCoroutineFileSystem.delete(zero)
                assertEquals(null, repository.readCreatedAt(index))
                assertEquals(updated, repository.readUpdatedAt(index))
                assertEquals(0, fileSystem.listCalls)

                SystemCoroutineFileSystem.writeString(zero, "invalid timestamp")
                assertFailsWith<Exception> { repository.readCreatedAt(index) }
                assertEquals(updated, repository.readUpdatedAt(index))
                SystemCoroutineFileSystem.delete(zero)
                SystemCoroutineFileSystem.writeString(Path(root, "sessions/$index/timestamp/2.json"), "broken")
                assertEquals(null, repository.readCreatedAt(index))
                assertFailsWith<Exception> { repository.readUpdatedAt(index) }
                assertEquals(0, fileSystem.listCalls)
            } finally {
                repository.closeAndJoin()
            }
        }

        test("persists an idempotent root archive marker without changing inventory") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val archivedIndex = repository.createInitialized(settings("archived"))
            val activeIndex = repository.createInitialized(settings("active"))
            val marker = Path(root, "sessions/$archivedIndex/$ArchiveMarkerFile")
            val archivedEntry = repository.getEntry(archivedIndex)

            archivedEntry.archive()
            archivedEntry.archive()

            assertEquals(listOf(archivedIndex, activeIndex), repository.list())
            assertEquals(
                listOf(activeIndex),
                repository.listEntries(includeArchived = false).map { it.entryIndex },
            )
            val allEntries = repository.listEntries(includeArchived = true)
            assertEquals(listOf(archivedIndex, activeIndex), allEntries.map { it.entryIndex })
            assertEquals(listOf("archived", "active"), allEntries.map { it.threadName })
            assertEquals(listOf(true, false), allEntries.map { it.archived })
            assertTrue(SystemCoroutineFileSystem.exists(marker))

            archivedEntry.unarchive()
            archivedEntry.unarchive()

            assertFalse(SystemCoroutineFileSystem.exists(marker))
            assertEquals(
                listOf(archivedIndex, activeIndex),
                repository.listEntries(includeArchived = false).map { it.entryIndex },
            )

            repository.closeAndJoin()
            val reopened = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            assertEquals(
                listOf(archivedIndex, activeIndex),
                reopened.listEntries(includeArchived = false).map { it.entryIndex },
            )
            reopened.closeAndJoin()
        }

        test("archived roots remain openable, forkable, deletable, and reusable") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("source"))
            val source = repository.open(sourceIndex)
            repository.listEntries()
                .single { entry -> entry.entryIndex == sourceIndex }
                .archive()

            assertSame(source, repository.open(sourceIndex))

            val forkIndex = repository.createFork(sourceIndex)
            assertFalse(
                SystemCoroutineFileSystem.exists(
                    Path(root, "sessions/$forkIndex/$ArchiveMarkerFile"),
                ),
            )

            repository.delete(sourceIndex)
            assertEquals(sourceIndex, repository.create())
            assertFalse(
                SystemCoroutineFileSystem.exists(
                    Path(root, "sessions/$sourceIndex/$ArchiveMarkerFile"),
                ),
            )

            repository.delete(sourceIndex)
            repository.delete(forkIndex)
            repository.closeAndJoin()
        }

        test("skips archived root metadata and timeline scanning by default") { root ->
            val setup = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val archivedIndex = setup.createInitialized(settings("archived"))
            val activeIndex = setup.createInitialized(settings("active"))
            setup.listEntries()
                .single { entry -> entry.entryIndex == archivedIndex }
                .archive()
            setup.closeAndJoin()

            val fileSystem = CountingListFileSystem()
            val repository = FileSystemKodexSessionRepository(
                root = root,
                dependencies = testKodexAgentDependencies(),
                fileSystem = fileSystem,
            )
            fileSystem.reset()

            assertEquals(
                listOf(activeIndex),
                repository.listEntries(includeArchived = false).map { it.entryIndex },
            )
            assertEquals(0, fileSystem.listCalls)

            fileSystem.reset()
            assertEquals(
                listOf(archivedIndex, activeIndex),
                repository.listEntries(includeArchived = true).map { it.entryIndex },
            )
            assertEquals(0, fileSystem.listCalls)

            repository.closeAndJoin()
        }

        test("scans a dangling latest file without repair while the root lease is held") { root ->
            val writer = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = writer.createInitialized(settings("active"))
            val lastActivityAt = Instant.parse("2026-08-24T06:00:00Z")
            writer.open(index).storage.timestamp[2] = lastActivityAt
            val latest = Path(root, "sessions/$index/timestamp/latest.json")
            SystemCoroutineFileSystem.writeString(latest, "3")

            val fileSystem = CountingListFileSystem()
            val reader = FileSystemKodexSessionRepository(
                root = root,
                dependencies = testKodexAgentDependencies(),
                fileSystem = fileSystem,
            )
            fileSystem.reset()

            assertEquals(
                listOf(Triple(index, "active", lastActivityAt)),
                reader.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.threadName, entry.lastActivityAt)
                },
            )
            assertEquals(1, fileSystem.listCalls)
            assertEquals("3", SystemCoroutineFileSystem.readString(latest))

            fileSystem.reset()
            reader.listEntries()
            assertEquals(1, fileSystem.listCalls)
            assertEquals("3", SystemCoroutineFileSystem.readString(latest))

            reader.closeAndJoin()
            writer.closeAndJoin()
        }

        test("repairs a dangling latest file while the root lease is available") { root ->
            val writer = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = writer.createInitialized(settings("crashed"))
            val lastActivityAt = Instant.parse("2026-08-24T06:05:00Z")
            writer.open(index).storage.timestamp[2] = lastActivityAt
            writer.closeAndJoin()
            val latest = Path(root, "sessions/$index/timestamp/latest.json")
            val lock = Path(root, "sessions/$index/lock.json")
            SystemCoroutineFileSystem.writeString(latest, "3")

            val fileSystem = CountingListFileSystem()
            val reader = FileSystemKodexSessionRepository(
                root = root,
                dependencies = testKodexAgentDependencies(),
                fileSystem = fileSystem,
            )
            fileSystem.reset()

            assertEquals(
                listOf(Triple(index, "crashed", lastActivityAt)),
                reader.listEntries().map { entry ->
                    Triple(entry.entryIndex, entry.threadName, entry.lastActivityAt)
                },
            )
            assertEquals(1, fileSystem.listCalls)
            assertEquals("2", SystemCoroutineFileSystem.readString(latest))
            assertFalse(SystemCoroutineFileSystem.exists(lock))

            fileSystem.reset()
            reader.listEntries()
            assertEquals(0, fileSystem.listCalls)
            reader.closeAndJoin()
        }

        test("releases a repair lease when its owner scope is cancelled") { root ->
            val writer = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = writer.createInitialized(settings("cancelled repair"))
            writer.open(index).storage.timestamp[2] = Instant.parse("2026-08-24T06:10:00Z")
            writer.closeAndJoin()
            val latest = Path(root, "sessions/$index/timestamp/latest.json")
            val lock = Path(root, "sessions/$index/lock.json")
            SystemCoroutineFileSystem.writeString(latest, "3")

            val fileSystem = SuspendingTimelineListFileSystem("timestamp")
            val reader = FileSystemKodexSessionRepository(
                root = root,
                dependencies = testKodexAgentDependencies(),
                fileSystem = fileSystem,
            )
            val listing = async {
                reader.listEntries()
            }
            fileSystem.listStarted.await()
            assertTrue(SystemCoroutineFileSystem.exists(lock))

            reader.closeAndJoin()

            assertFailsWith<CancellationException> { listing.await() }
            assertFalse(SystemCoroutineFileSystem.exists(lock))
            assertEquals("3", SystemCoroutineFileSystem.readString(latest))
        }

        test("owns a root lease under the repository scope") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = repository.create()
            repository.open(index).runtime.modify { storage ->
                storage.initialize(settings("owned"))
            }
            val lock = Path(root, "sessions/$index/lock.json")
            assertEquals(true, SystemCoroutineFileSystem.exists(lock))

            repository.closeAndJoin()

            assertFalse(SystemCoroutineFileSystem.exists(lock))
            val reopened = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            assertEquals("owned", reopened.open(index).storage.settings[0].threadName)
            reopened.closeAndJoin()
        }

        test("reconciles a dangling latest file when opening a session") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = repository.createInitialized(settings("indexed"))
            repository.closeAndJoin()
            val latest = Path(root, "sessions/$index/settings/latest.json")
            SystemCoroutineFileSystem.writeString(latest, "100")

            val reopened = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            reopened.open(index)

            assertEquals("0", SystemCoroutineFileSystem.readString(latest))
            reopened.closeAndJoin()
        }

        test("retries when another repository claims its next root slot") { root ->
            val staleRepository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val competingRepository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())

            assertEquals(0, competingRepository.create())
            assertEquals(1, staleRepository.create())
            assertEquals(listOf(1), staleRepository.entries.value)

            staleRepository.closeAndJoin()
            competingRepository.closeAndJoin()

            val reopened = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            assertEquals(listOf(0, 1), reopened.entries.value)
            reopened.closeAndJoin()
        }

        test("a root lease excludes another repository until shutdown") { root ->
            val first = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val second = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = first.createInitialized(settings())
            first.open(index)

            assertFailsWith<FileSystemLeaseInUseException> { second.open(index) }

            first.closeAndJoin()
            assertEquals("Session 0", second.open(index).storage.settings[0].threadName)
            second.closeAndJoin()
        }

        test("delete invalidates the cached root and releases its slot") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = repository.createInitialized(settings())
            val session = repository.open(index)

            repository.delete(index)

            assertFailsWith<IllegalStateException> { session.storage.settings.latestIndex() }
            assertEquals(0, repository.createInitialized(settings()))
            repository.closeAndJoin()
        }

        test("fork is a downstream operation and does not copy descendants") { root ->
            val sourceCwd = Path(root, "source-workspace")
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            try {
                val sourceIndex = repository.createInitialized(settings("Source", sourceCwd))
                val source = repository.open(sourceIndex)
                source.runtime.injectHistory(listOf(userMessage("copied")))

                val targetIndex = repository.createFork(sourceIndex)
                val target = repository.open(targetIndex)
                val latest = target.storage.latestIndex()
                target.runtime.updateSettings(
                    target.storage.settings[latest].copy(threadName = "[fork] Source"),
                )

                // Snapshot zero initializes settings, not an index event.
                assertEquals(listOf(1), source.storage.index.indexesIn(0..latest))
                assertEquals(
                    source.storage.index.indexesIn(0..latest),
                    target.storage.index.indexesIn(0..latest),
                )
                assertEquals(userMessage("copied"), target.storage.index[1])
                assertEquals("[fork] Source", target.storage.settings[2].threadName)
                assertEquals(sourceCwd, target.storage.settings[2].cwd)
            } finally {
                repository.closeAndJoin()
            }
        }

        test("failed fork removes its reserved session") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val sourceIndex = repository.createInitialized(settings("Source"))
            val entriesBefore = repository.list()

            assertFailsWith<IllegalArgumentException> {
                repository.createFork(sourceEntryIndex = sourceIndex + 1)
            }

            assertEquals(entriesBefore, repository.list())
            assertEquals(1, repository.create())
            repository.closeAndJoin()
        }

        test("owns each runtime for the complete Agent session lifecycle") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val session = repository.open(repository.createInitialized(settings("root")))

            assertSame(session.storage, session.runtime.storage)

            session.coroutineContext[Job]?.cancelAndJoin()

            assertFalse(session.runtime.coroutineContext[Job]?.isActive ?: true)
            repository.closeAndJoin()
        }

    }
}

/** Real filesystem gate: publish settings 1, fail its temp delete, then fail the rescan. */
private class SettingsPublicationRescanFaultFileSystem(
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    private var targetDirectory: Path? = null
    private var primary: Throwable? = null
    private var secondary: Throwable? = null
    private var published = false
    var targetListCount: Int = 0
        private set

    fun arm(directory: Path, primary: Throwable, secondary: Throwable) {
        targetDirectory = directory
        this.primary = primary
        this.secondary = secondary
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        delegate.atomicMove(source, destination)
        val directory = targetDirectory ?: return
        if (primary != null && destination == Path(directory, "1.json")) published = true
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (published && path.parent == targetDirectory && path.name.endsWith(".tmp")) {
            primary?.let { failure ->
                primary = null
                throw failure
            }
        }
        delegate.delete(path, mustExist)
    }

    override suspend fun list(directory: Path): Collection<Path> {
        if (directory == targetDirectory) {
            targetListCount += 1
            if (published && primary == null) {
                secondary?.let { failure ->
                    secondary = null
                    throw failure
                }
            }
        }
        return delegate.list(directory)
    }
}

private class CountingListFileSystem(
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    var listCalls: Int = 0
        private set

    override suspend fun list(directory: Path): Collection<Path> {
        listCalls += 1
        return delegate.list(directory)
    }

    fun reset() {
        listCalls = 0
    }
}

private class SuspendingTimelineListFileSystem(
    private val timelineName: String,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    val listStarted = CompletableDeferred<Unit>()

    override suspend fun list(directory: Path): Collection<Path> {
        if (directory.name == timelineName) {
            listStarted.complete(Unit)
            awaitCancellation()
        }
        return delegate.list(directory)
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private suspend fun KodexSessionRepository.closeAndJoin() {
    cancel()
    coroutineContext[Job]?.join()
}
