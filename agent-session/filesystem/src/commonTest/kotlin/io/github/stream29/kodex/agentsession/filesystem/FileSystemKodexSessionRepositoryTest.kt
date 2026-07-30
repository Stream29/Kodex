package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.forkTo
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
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

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )

private fun pendingTool(callId: String): PendingToolEvent =
    PendingCustomToolEvent(
        callId = callId,
        name = "tool-$callId",
        input = "input-$callId",
    )

private suspend fun KodexAgentSession.spawnInitialized(name: String): KodexAgentSession =
    subagents.open(subagents.create()).also { child ->
        child.runtime.modify { target -> storage.forkTo(1, target) }
        child.runtime.updateSettings(settings(name))
    }

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
            val stableEvent = StableCleanEvent.AssistantMessage(
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
            session.storage.timestamp[1] = timestamp
            session.storage.stable[1] = stableEvent
            session.storage.unstable[1] = pendingEvents
            val childEntry = session.subagents.create()

            val directory = Path(root, "sessions/0")
            assertEquals(
                setOf(
                    "compaction",
                    "settings",
                    "timestamp",
                    "token-count",
                    "stable",
                    "unstable",
                    "subagents",
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
            assertEquals(listOf(childEntry), reopenedSession.subagents.entries.value)
            assertEquals(storageId, reopenedSession.storage.id)
            assertEquals(cwd, reopenedSession.storage.settings[0].cwd)
            assertEquals(stableEvent, reopenedSession.storage.stable[1])
            assertEquals(pendingEvents, reopenedSession.storage.unstable[1])
            reopened.closeAndJoin()
        }

        test("returns one cached root instance and keeps children in numeric order") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val index = repository.createInitialized(settings("root"))
            val session = repository.open(index)
            val children = buildList {
                repeat(11) { childIndex -> add(session.spawnInitialized("child-$childIndex")) }
            }
            val nested = children.first().spawnInitialized("nested")

            assertSame(session, repository.open(index))
            assertEquals(children.map { it.storage.id }, session.subagents.list().map { entry -> session.subagents.open(entry).storage.id })
            assertEquals(listOf(nested.storage.id), children.first().subagents.list().map { entry -> children.first().subagents.open(entry).storage.id })
            assertEquals(
                (0..10).map(Int::toString),
                SystemCoroutineFileSystem.list(Path(root, "sessions/0/subagents"))
                    .map(Path::name)
                    .sortedBy(String::toInt),
            )
            repository.closeAndJoin()
        }

        test("each Agent manages its direct entries") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val rootSession = repository.open(repository.createInitialized(settings("root")))
            val first = rootSession.subagents.create()
            val second = rootSession.subagents.create()

            assertEquals(listOf(first, second), rootSession.subagents.list())
            assertEquals(-1, rootSession.subagents.open(first).storage.latestIndex())

            rootSession.subagents.delete(first)

            assertEquals(listOf(second), rootSession.subagents.list())
            assertFailsWith<IllegalArgumentException> { rootSession.subagents.open(first) }
            repository.closeAndJoin()
        }

        test("publishes ordered direct entry snapshots") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())

            assertEquals(emptyList(), repository.entries.value)
            val rootIndex = repository.create()
            assertEquals(listOf(rootIndex), repository.entries.value)
            val rootSession = repository.open(rootIndex)
            assertEquals(emptyList(), rootSession.subagents.entries.value)

            val first = rootSession.subagents.create()
            val second = rootSession.subagents.create()
            assertEquals(listOf(first, second), rootSession.subagents.entries.value)

            rootSession.subagents.delete(first)
            assertEquals(listOf(second), rootSession.subagents.entries.value)
            assertEquals(first, rootSession.subagents.create())
            assertEquals(listOf(first, second), rootSession.subagents.entries.value)

            repository.delete(rootIndex)
            assertEquals(emptyList(), repository.entries.value)
            repository.closeAndJoin()
        }

        test("allocates the next slot when an earlier root directory already exists") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())

            val first = repository.createInitialized(settings("first"))
            val second = repository.createInitialized(settings("second"))
            val firstLastActivityAt = Instant.parse("2026-07-31T10:00:00Z")
            val secondLastActivityAt = Instant.parse("2026-07-31T10:05:00Z")
            repository.open(first).storage.timestamp[1] = firstLastActivityAt
            repository.open(second).storage.timestamp[1] = secondLastActivityAt

            assertEquals(0, first)
            assertEquals(1, second)
            assertEquals(
                listOf(
                    KodexSessionEntry(first, "first", firstLastActivityAt),
                    KodexSessionEntry(second, "second", secondLastActivityAt),
                ),
                repository.listEntries(),
            )
            repository.closeAndJoin()
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
            val child = session.spawnInitialized("child")

            repository.delete(index)

            assertFailsWith<IllegalStateException> { session.storage.settings.latestIndex() }
            assertFailsWith<IllegalStateException> { child.storage.settings.latestIndex() }
            assertEquals(0, repository.createInitialized(settings()))
            repository.closeAndJoin()
        }

        test("fork is a downstream operation and does not copy descendants") { root ->
            val sourceRepository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceCwd = Path(root, "source-workspace")
            val sourceIndex = sourceRepository.createInitialized(settings("Source", sourceCwd))
            val source = sourceRepository.open(sourceIndex)
            source.runtime.injectHistory(listOf(userMessage("copied")))
            source.spawnInitialized("child")

            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val targetIndex = repository.create()
            val target = repository.open(targetIndex)
            val latest = target.runtime.modify { storage ->
                source.storage.forkTo(2, storage)
                storage.latestIndex()
            }
            target.runtime.updateSettings(
                target.storage.settings[latest].copy(threadName = "[fork] Source"),
            )

            assertEquals(listOf(1), target.storage.stable.indexes().toList())
            assertEquals(userMessage("copied"), target.storage.stable[1])
            assertEquals("[fork] Source", target.storage.settings[2].threadName)
            assertEquals(sourceCwd, target.storage.settings[2].cwd)
            assertEquals(emptyList(), target.subagents.list())
            repository.closeAndJoin()
            sourceRepository.closeAndJoin()
        }

        test("owns each runtime for the complete Agent session lifecycle") { root ->
            val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
            val session = repository.open(repository.createInitialized(settings("root")))
            val child = session.spawnInitialized("child")

            assertSame(session.storage, session.runtime.storage)
            assertSame(child.storage, child.runtime.storage)

            session.coroutineContext[Job]?.cancelAndJoin()

            assertFalse(session.runtime.coroutineContext[Job]?.isActive ?: true)
            assertFalse(child.runtime.coroutineContext[Job]?.isActive ?: true)
            repository.closeAndJoin()
        }

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
