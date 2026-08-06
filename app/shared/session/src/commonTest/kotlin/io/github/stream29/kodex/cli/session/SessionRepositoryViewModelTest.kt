package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    test("forks a subagent prefix into a titled root without descendants") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceSessionIndex = repository.create()
            val sourceRoot = repository.open(sourceSessionIndex)
            sourceRoot.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Source root",
                    ),
                )
            }
            val sourceChild = sourceRoot.subagents.open(sourceRoot.subagents.create())
            val boundarySettings = KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = "Child boundary",
            )
            sourceChild.runtime.modify { storage ->
                storage.initialize(boundarySettings)
                storage.stable[1] = StableCleanEvent.UserMessage(
                    listOf(ContentItem.InputText("retained")),
                )
                storage.stable[4] = StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("fork here")),
                )
                storage.stable[7] = StableCleanEvent.UserMessage(
                    listOf(ContentItem.InputText("source suffix")),
                )
            }
            sourceChild.subagents.create()
            val viewModel = SessionRepositoryViewModel(repository)
            try {
                val targetViewModel = viewModel.fork(
                    source = sourceChild,
                    untilExclusive = 5,
                )
                val target = targetViewModel.rootSession
                val targetIndex = requireNotNull(viewModel.state.value.selectedSessionIndex)

                assertNotEquals(sourceChild.storage.id, target.storage.id)
                assertEquals(7, sourceChild.storage.latestIndex())
                assertEquals(4, target.storage.stable.floorToIndex(Int.MAX_VALUE))
                assertEquals(
                    StableCleanEvent.AssistantMessage(
                        listOf(ContentItem.OutputText("fork here")),
                    ),
                    target.storage.stable[4],
                )
                assertEquals(5, target.storage.latestIndex())
                assertEquals("[fork] Child boundary", target.storage.settings[5].threadName)
                assertEquals(targetIndex, viewModel.state.value.sessions.single { it.selected }.sessionIndex)
                assertTrue(target.subagents.entries.value.isEmpty())
                assertEquals(1, sourceChild.subagents.entries.value.size)
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("fork title falls back to the new session index when the boundary title is blank") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sourceIndex = repository.create()
            val source = repository.open(sourceIndex)
            source.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "",
                    ),
                )
                storage.stable[1] = StableCleanEvent.UserMessage(
                    listOf(ContentItem.InputText("fork")),
                )
            }
            val viewModel = SessionRepositoryViewModel(repository)
            try {
                val target = viewModel.fork(source = source, untilExclusive = 2)
                val targetIndex = requireNotNull(viewModel.state.value.selectedSessionIndex)

                assertEquals(
                    "[fork] Session $targetIndex",
                    target.rootSession.storage.settings[2].threadName,
                )
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}
