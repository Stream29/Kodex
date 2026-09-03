package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
        test("creates an uninitialized root storage") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = repository.create()
            val session = repository.open(index)

            assertEquals(-1, session.storage.latestIndex())
            session.runtime.modify { storage -> storage.initialize(settings("root")) }
            assertEquals(0, session.storage.latestIndex())
            assertEquals(0L, session.storage.tokenCount[0])
            repository.closeAndJoin()
        }

        test("persists canonical root layout and lightweight entries") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val cwd = Path(root, "workspace")
            val index = repository.createInitialized(settings(cwd = cwd))
            val session = repository.open(index)
            val storageId = session.storage.id
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
            assertEquals(storageId, reopenedSession.storage.id)
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
            val sourceIndex = repository.createInitialized(settings("Source", sourceCwd))
            val source = repository.open(sourceIndex)
            source.runtime.injectHistory(listOf(userMessage("copied")))

            val targetIndex = repository.createFork(sourceIndex)
            val target = repository.open(targetIndex)
            val latest = target.storage.latestIndex()
            target.runtime.updateSettings(
                target.storage.settings[latest].copy(threadName = "[fork] Source"),
            )

            assertEquals(listOf(0, 1), target.storage.index.indexesIn(0..latest))
            assertEquals(userMessage("copied"), target.storage.index[1])
            assertEquals("[fork] Source", target.storage.settings[2].threadName)
            assertEquals(sourceCwd, target.storage.settings[2].cwd)
            repository.closeAndJoin()
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
