package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

val sessionRepositoryViewModelTest by testSuite {
    test("publishes catalog titles without materializing root view models") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            val lastActivityAt = Instant.parse("2026-07-31T10:00:00Z")
            repository.open(sessionIndex).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Review session title catalog",
                    ),
                )
            }
            repository.open(sessionIndex).storage.timestamp[1] = lastActivityAt
            val viewModel = SessionRepositoryViewModel(repository)
            try {
                viewModel.refresh()

                val entry = viewModel.state.value.sessions.single()
                assertEquals("Review session title catalog", entry.threadName)
                assertEquals(lastActivityAt, entry.lastActivityAt)
                assertNull(entry.viewModel)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("orders catalog by last activity without materializing root view models") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val oldest = repository.create()
            val newest = repository.create()
            val uninitialized = repository.create()
            repository.open(oldest).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Oldest",
                    ),
                )
            }
            repository.open(newest).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Newest",
                    ),
                )
            }
            repository.open(oldest).storage.timestamp[1] = Instant.parse("2026-07-31T10:00:00Z")
            repository.open(newest).storage.timestamp[1] = Instant.parse("2026-07-31T10:05:00Z")
            val viewModel = SessionRepositoryViewModel(repository)
            try {
                viewModel.refresh()

                assertEquals(
                    listOf(newest, oldest, uninitialized),
                    viewModel.state.value.sessions.map { entry -> entry.sessionIndex },
                )
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}
