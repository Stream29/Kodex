package io.github.stream29.codex.lite.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentsession.inmemory.InMemoryCodexSessionRepository
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.filesystemlease.FileSystemLeaseInUseException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Instant

private suspend fun temporaryRepositoryRoot(): Path =
    Path(SystemTemporaryDirectory, "codex-lite-session-${Random.nextLong()}").also { root ->
        SystemCoroutineFileSystem.createDirectories(root)
    }

private fun settings(name: String = ""): CodexAgentSettings =
    CodexAgentSettings(model = OpenAiModelId("test-model"), threadName = name)

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

private suspend fun CodexAgentSession.spawnInitialized(name: String): CodexAgentSession =
    subagents.open(subagents.create()).also { child ->
        storage.forkTo(until = 1, target = child.storage)
        child.storage.settings[1] = settings(name)
    }

private suspend fun CodexSessionRepository.createInitialized(
    settings: CodexAgentSettings,
): Int {
    val index = create()
    open(index).storage.initialize(settings.copy(threadName = settings.threadName.ifEmpty { "Session $index" }))
    return index
}

val fileSystemCodexSessionRepositoryTest by testSuite {
    testFixture { temporaryRepositoryRoot() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("creates an uninitialized root storage") { root ->
            val repository = FileSystemCodexSessionRepository(root)
            val index = repository.create()
            val session = repository.open(index)

            assertEquals(-1, session.storage.latestIndex())
            session.storage.initialize(settings("root"))
            assertEquals(0, session.storage.latestIndex())
            repository.closeAndJoin()
        }

        test("persists canonical root layout and lightweight entries") { root ->
            val repository = FileSystemCodexSessionRepository(root)
            val index = repository.createInitialized(settings())
            val session = repository.open(index)
            val timestamp = Instant.parse("2026-07-22T00:00:00Z")
            session.storage.history[1] = userMessage("persisted")
            session.storage.timestamp[1] = timestamp

            val directory = Path(root, "sessions/0")
            assertEquals(
                setOf("history", "compaction", "settings", "timestamp", "token-count", "subagents", "lock.json"),
                SystemCoroutineFileSystem.list(directory)
                    .map(Path::name)
                    .filterNot { name -> name.startsWith(".") }
                    .toSet(),
            )
            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "manifest.json")))
            assertEquals(listOf(index), repository.list())
            repository.closeAndJoin()

            val reopened = FileSystemCodexSessionRepository(root)
            val reopenedSession = reopened.open(index)
            assertEquals(session.storage.id, reopenedSession.storage.id)
            assertEquals(userMessage("persisted"), reopenedSession.storage.history[1])
            reopened.closeAndJoin()
        }

        test("returns one cached root instance and keeps children in numeric order") { root ->
            val repository = FileSystemCodexSessionRepository(root)
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
            val repository = FileSystemCodexSessionRepository(root)
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

        test("allocates the next slot when an earlier root directory already exists") { root ->
            val repository = FileSystemCodexSessionRepository(root)

            assertEquals(0, repository.createInitialized(settings("first")))
            assertEquals(1, repository.createInitialized(settings("second")))

            repository.closeAndJoin()
        }

        test("a root lease excludes another repository until shutdown") { root ->
            val first = FileSystemCodexSessionRepository(root)
            val second = FileSystemCodexSessionRepository(root)
            val index = first.createInitialized(settings())
            first.open(index)

            assertFailsWith<FileSystemLeaseInUseException> { second.open(index) }

            first.closeAndJoin()
            assertEquals("Session 0", second.open(index).storage.settings[0].threadName)
            second.closeAndJoin()
        }

        test("delete invalidates the cached root and releases its slot") { root ->
            val repository = FileSystemCodexSessionRepository(root)
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
            val sourceRepository = InMemoryCodexSessionRepository()
            val sourceIndex = sourceRepository.createInitialized(settings("Source"))
            val source = sourceRepository.open(sourceIndex)
            source.storage.history[1] = userMessage("copied")
            source.spawnInitialized("child")

            val repository = FileSystemCodexSessionRepository(root)
            val targetIndex = repository.createInitialized(settings("temporary"))
            val target = repository.open(targetIndex)
            source.storage.forkTo(until = 2, target = target.storage)
            val latest = target.storage.settings.indexes().toList().last()
            target.storage.settings[latest + 1] = target.storage.settings[latest].copy(threadName = "[fork] Source")

            assertEquals(listOf(1), target.storage.history.indexes().toList())
            assertEquals(userMessage("copied"), target.storage.history[1])
            assertEquals("[fork] Source", target.storage.settings[2].threadName)
            assertEquals(emptyList(), target.subagents.list())
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

private suspend fun FileSystemCodexSessionRepository.closeAndJoin() {
    cancel()
    coroutineContext[Job]?.join()
}
