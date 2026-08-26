package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

val sessionRepositoryViewModelTest by testSuite {
    test("catalog publishes loading and orders lightweight entries by activity") {
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
            val catalog = createSessionCatalogViewModelFactory(
                KodexSessionRepositoryFactory { repository },
                this,
            ).create { sessionIndex -> store.delete(sessionIndex) }
            try {
                assertEquals(SessionCatalogState.Unloaded, catalog.state.value)
                val observedStates = mutableListOf<SessionCatalogState>()
                val stateCollector = launch(Dispatchers.Unconfined) {
                    catalog.state.take(3).toList(observedStates)
                }

                catalog.refresh()
                stateCollector.join()

                val loaded = assertIs<SessionCatalogState.Loaded>(catalog.state.value)
                assertEquals(
                    listOf(newest, oldest),
                    loaded.sessions.map { entry -> entry.sessionIndex },
                )
                assertEquals(
                    listOf("Newest", "Oldest"),
                    loaded.sessions.map { entry -> entry.threadName },
                )
                assertEquals(
                    listOf(
                        SessionCatalogState.Unloaded,
                        SessionCatalogState.Loading(false),
                        loaded,
                    ),
                    observedStates,
                )
            } finally {
                catalog.close()
                store.shutdown()
                repository.cancelAndJoin()
            }
        }
    }

    test("catalog filters and updates root archive state atomically") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val archived = repository.create()
            val active = repository.create()
            initialize(repository, archived, "Archived")
            initialize(repository, active, "Active")
            repository.listEntries()
                .single { entry -> entry.entryIndex == archived }
                .archive()
            val catalog = createSessionCatalogViewModelFactory(
                KodexSessionRepositoryFactory { repository },
                this,
            ).create { false }
            try {
                catalog.refresh()
                assertEquals(false, catalog.state.value.showArchived)
                assertEquals(listOf(active), catalog.state.value.sessions.map { it.sessionIndex })

                catalog.setShowArchived(true)
                val allSessions = assertIs<SessionCatalogState.Loaded>(catalog.state.value)
                assertEquals(true, allSessions.showArchived)
                assertEquals(listOf(active, archived), allSessions.sessions.map { it.sessionIndex })
                assertTrue(allSessions.sessions.last().archived)

                catalog.archive(active)
                val allArchived = assertIs<SessionCatalogState.Loaded>(catalog.state.value)
                assertTrue(allArchived.sessions.all { it.archived })

                catalog.setShowArchived(false)
                assertEquals(emptyList(), catalog.state.value.sessions)

                catalog.setShowArchived(true)
                catalog.unarchive(archived)
                val restored = assertIs<SessionCatalogState.Loaded>(catalog.state.value)
                assertEquals(false, restored.sessions.last().archived)
            } finally {
                catalog.close()
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
