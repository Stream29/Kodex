package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.time.Instant

val sessionRepositoryViewModelTest by testSuite {
    test("catalog stays lazy and orders lightweight entries by activity") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val oldest = repository.create()
            val newest = repository.create()
            initialize(repository, oldest, "Oldest")
            initialize(repository, newest, "Newest")
            repository.open(oldest).storage.timestamp[1] =
                Instant.parse("2026-07-31T10:00:00Z")
            repository.open(newest).storage.timestamp[1] =
                Instant.parse("2026-07-31T10:05:00Z")
            val store = testSessionViewModelRegistry(repository, this)
            val catalog = createSessionCatalogViewModelFactory(repository, this).create()
            try {
                assertEquals(emptyList(), catalog.sessions.value)

                catalog.refresh()

                assertEquals(
                    listOf(newest, oldest),
                    catalog.sessions.value.map { entry -> entry.sessionIndex },
                )
                assertEquals(
                    listOf("Newest", "Oldest"),
                    catalog.sessions.value.map { entry -> entry.threadName },
                )
            } finally {
                catalog.close()
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("delete removes persisted data and any opened handle") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            initialize(repository, sessionIndex, "Delete me")
            val store = testSessionViewModelRegistry(repository, this)
            try {
                store.open(sessionIndex)

                assertEquals(true, store.delete(sessionIndex))
                assertEquals(emptyList(), repository.list())
                assertEquals(false, store.delete(sessionIndex))
            } finally {
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun initialize(
    repository: InMemoryKodexSessionRepository,
    index: Int,
    name: String,
) {
    repository.open(index).runtime.modify { storage ->
        storage.initialize(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = name,
            ),
        )
    }
}
