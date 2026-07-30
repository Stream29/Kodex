package io.github.stream29.kodex.agentsession.multiagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.listChild
import io.github.stream29.kodex.agentsession.contract.parentOf
import io.github.stream29.kodex.agentsession.contract.pathOf
import io.github.stream29.kodex.agentsession.contract.rootSession
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.coroutines.cancel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

val agentPathResolverImplTest by testSuite {
    test("resolves root, descendants, and cross-branch absolute paths") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        try {
            val root = repository.initializedSession("Root title")
            val researcher = root.subagents.initializedSession("/root/researcher")
            val worker = researcher.subagents.initializedSession("/root/researcher/worker")
            val reviewer = root.subagents.initializedSession("/root/reviewer")
            val resolver = AgentPathResolverImpl(root)

            assertSame(root, resolver.resolveOrNull("/root"))
            assertSame(researcher, resolver.resolveOrNull("/root/researcher"))
            assertSame(worker, resolver.resolveOrNull("/root/researcher/worker"))
            assertSame(reviewer, resolver.resolveOrNull("/root/reviewer"))
        } finally {
            repository.cancel()
        }
    }

    test("observes renamed paths without refreshing a cache") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        try {
            val root = repository.initializedSession("Root title")
            val child = root.subagents.initializedSession("/root/worker")
            val resolver = AgentPathResolverImpl(root)

            assertSame(child, resolver.resolveOrNull("/root/worker"))
            child.runtime.updateSettings(child.storage.settings.latestValue().copy(
                threadName = "/root/reviewer",
            ))

            assertSame(child, resolver.resolveOrNull("/root/reviewer"))
            assertNull(resolver.resolveOrNull("/root/worker"))
            assertEquals("/root/reviewer", resolver.pathOf(child))
            assertSame(root, resolver.parentOf(child))
        } finally {
            repository.cancel()
        }
    }

    test("navigates roots, paths, parents, and initialized children") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        try {
            val root = repository.initializedSession("/root")
            val researcher = root.subagents.initializedSession("/root/researcher")
            val worker = researcher.subagents.initializedSession("/root/researcher/worker")
            val reviewer = root.subagents.initializedSession("/root/reviewer")
            root.subagents.create()
            val resolver = AgentPathResolverImpl(root)

            assertSame(root, resolver.rootSession())
            assertEquals("/root", resolver.pathOf(root))
            assertEquals("/root/researcher", resolver.pathOf(researcher))
            assertEquals("/root/researcher/worker", resolver.pathOf(worker))
            assertNull(resolver.parentOf(root))
            assertSame(root, resolver.parentOf(researcher))
            assertSame(researcher, resolver.parentOf(worker))
            assertEquals(listOf(researcher, reviewer), resolver.listChild(root))
            assertEquals(listOf(worker), resolver.listChild(researcher))
        } finally {
            repository.cancel()
        }
    }

    test("ignores uninitialized entries and requires canonical paths") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        try {
            val root = repository.initializedSession("Root title")
            root.subagents.create()
            val resolver = AgentPathResolverImpl(root)

            assertSame(root, resolver.resolveOrNull("/root"))
            assertNull(resolver.resolveOrNull("worker"))
            assertNull(resolver.resolveOrNull("/root/missing"))
        } finally {
            repository.cancel()
        }
    }
}

private suspend fun InMemoryKodexSessionRepository.initializedSession(
    threadName: String,
): KodexAgentSession = initializedSession(create(), threadName)

private suspend fun KodexSessionRepository.initializedSession(
    entryIndex: Int,
    threadName: String,
): KodexAgentSession =
    open(entryIndex).also { session ->
        session.runtime.modify { storage ->
            storage.initialize(
                KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    threadName = threadName,
                ),
            )
        }
    }

private suspend fun KodexSessionRepository.initializedSession(
    threadName: String,
): KodexAgentSession = initializedSession(create(), threadName)
