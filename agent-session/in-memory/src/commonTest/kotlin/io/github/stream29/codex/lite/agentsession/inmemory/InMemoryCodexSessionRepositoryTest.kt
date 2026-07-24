package io.github.stream29.codex.lite.agentsession.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

val inMemoryCodexSessionRepositoryTest by testSuite {
    test("creates an uninitialized root storage") {
        val repository = InMemoryCodexSessionRepository()
        val index = repository.create()
        val session = repository.open(index)

        assertEquals(-1, session.storage.latestIndex())
        session.storage.initialize(settings("root"))
        assertEquals(0, session.storage.latestIndex())
    }

    test("creates zero-based root entries") {
        val repository = InMemoryCodexSessionRepository()
        val first = repository.createInitialized(settings())
        val second = repository.createInitialized(settings("Named"))

        assertEquals(0, first)
        assertEquals(1, second)
        assertEquals("Session 0", repository.open(first).storage.settings[0].threadName)
        assertEquals(listOf(first, second), repository.list())
    }

    test("returns one cached root instance and persists its recursive tree") {
        val repository = InMemoryCodexSessionRepository()
        val index = repository.createInitialized(settings())
        val root = repository.open(index)
        val first = root.spawnInitialized("first")
        val second = root.spawnInitialized("second")
        val nested = first.spawnInitialized("nested")

        assertSame(root, repository.open(index))
        assertEquals(listOf(first.storage.id, second.storage.id), root.subagents.list().map { entry -> root.subagents.open(entry).storage.id })
        assertEquals(listOf(nested.storage.id), first.subagents.list().map { entry -> first.subagents.open(entry).storage.id })
    }

    test("each Agent manages its direct entries") {
        val repository = InMemoryCodexSessionRepository()
        val root = repository.open(repository.createInitialized(settings("root")))
        val first = root.subagents.create()
        val second = root.subagents.create()

        assertEquals(listOf(first, second), root.subagents.list())
        assertEquals(-1, root.subagents.open(first).storage.latestIndex())

        root.subagents.delete(first)

        assertEquals(listOf(second), root.subagents.list())
        assertFailsWith<IllegalArgumentException> { root.subagents.open(first) }
    }

    test("delete invalidates cached nodes and releases the numeric slot") {
        val repository = InMemoryCodexSessionRepository()
        val index = repository.createInitialized(settings())
        val root = repository.open(index)
        val child = root.spawnInitialized("child")

        repository.delete(index)

        assertFailsWith<IllegalStateException> { root.storage.settings.latestIndex() }
        assertFailsWith<IllegalStateException> { child.storage.settings.latestIndex() }
        assertEquals(0, repository.createInitialized(settings()))
    }

    test("fork is a downstream operation and does not copy descendants") {
        val repository = InMemoryCodexSessionRepository()
        val sourceIndex = repository.createInitialized(settings("Source"))
        val source = repository.open(sourceIndex)
        source.storage.history[1] = userMessage("copied")
        source.spawnInitialized("child")

        val targetIndex = repository.createInitialized(settings("temporary"))
        val target = repository.open(targetIndex)
        source.storage.forkTo(until = 2, target = target.storage)
        val latest = target.storage.latestIndex()
        target.storage.settings[latest + 1] = target.storage.settings[latest].copy(threadName = "[fork] Source")

        assertEquals(listOf(1), target.storage.history.indexes().toList())
        assertEquals(userMessage("copied"), target.storage.history[1])
        assertEquals("[fork] Source", target.storage.settings[2].threadName)
        assertEquals(emptyList(), target.subagents.list())
    }
}
