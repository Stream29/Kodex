@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.rpcexception.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Instant

val backendSessionManagementTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("catalog preserves missing dates and does not activate entries") {
        runtimeFixture { repository, initial, runtime ->
            val management = testSessionManagement(repository)
            assertTrue(management.deleteSession(initial))
            assertEquals(emptyList(), management.getSessionCatalog(false))
            val index = repository.create()
            val row = management.getSessionCatalog(false).single()
            assertEquals(index, row.sessionIndex)
            assertNull(row.threadName)
            assertNull(row.createdAt)
            assertNull(row.updatedAt)
            assertFalse(row.isActive)
            assertFalse(row.running)
            assertFailsWith<SessionNotActive> { runtime.getLatestIndex(index) }
        }
    }

    test("creation captures current backend defaults once and preserves prior settings title and identity semantics") {
        runtimeFixture { repository, _, runtime ->
            var defaults = testNewSessionDefaults()
            var captures = 0
            val management = testSessionManagement(repository, { captures++; defaults })
            val cwd = Path("/fixture/项目 with spaces")
            val first = management.createSession(cwd)
            val original = repository.openSessions.value.getValue(first).storage.getSettingsAt(0)
            defaults = KodexNewSessionSettings(
                model = OpenAiModelId("new-backend-model"),
                reasoningEffort = ReasoningEffort.High,
                serviceTier = ServiceTier.Ultrafast,
                requestUserInputMode = RequestUserInputMode.NoQuestion,
            )
            val second = management.createSession(cwd)
            val a = repository.openSessions.value.getValue(first).storage.getSettingsAt(0)
            val b = repository.openSessions.value.getValue(second).storage.getSettingsAt(0)
            assertEquals(2, captures)
            assertEquals(original, a)
            assertEquals(testNewSessionDefaults().model, a.model)
            assertEquals(defaults.model, b.model)
            assertEquals(defaults.reasoningEffort, b.reasoning.effort)
            assertEquals(defaults.serviceTier, b.serviceTier)
            assertEquals(defaults.requestUserInputMode, b.requestUserInputMode)
            assertEquals(cwd, a.cwd)
            assertEquals(cwd, b.cwd)
            assertEquals("Session $first", a.threadName)
            assertEquals("Session $second", b.threadName)
            assertNotEquals(a.turnId, b.turnId)
            assertNotEquals(a.windowId, b.windowId)
            assertEquals(0, runtime.getLatestIndex(first))
            assertNull(
                repository.openSessions.value.getValue(first).storage
                    .getCommitAtOrBefore(Int.MAX_VALUE, AgentCommitType.IndexTypes),
            )
            val row = management.getSessionCatalog(false).single { it.sessionIndex == first }
            assertNotNull(row.createdAt)
            assertEquals(row.createdAt, row.updatedAt)
            assertTrue(row.isActive)
            assertFalse(row.running)
        }
    }

    test("catalog ordering and timestamp zero stay distinct from later timestamps") {
        runtimeFixture { repository, _, _ ->
            val management = testSessionManagement(repository)
            val a = repository.create()
            val b = repository.create()
            val timestamp = Instant.parse("2099-01-01T00:00:00Z")
            for (index in listOf(a, b)) {
                val temporary = repository.open(index)
                try {
                    temporary.runtime.modify {
                        it.commit(KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "init-without-timestamp"))
                        it.commit(
                            KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "late"),
                            timestamp,
                        )
                    }
                } finally {
                    repository.closeSession(index)
                }
            }
            val entries = management.getSessionCatalog(false)
            assertEquals(listOf(b, a), entries.take(2).map { it.sessionIndex })
            entries.take(2).forEach {
                assertNotNull(it.createdAt)
                assertNotEquals(timestamp, it.createdAt)
                assertEquals(timestamp, it.updatedAt)
                assertFalse(it.isActive)
            }
        }
    }

    test("archive is idempotent and changes neither owner nor execution") {
        runtimeFixture { repository, index, _ ->
            val management = testSessionManagement(repository)
            val owner = repository.openSessions.value.getValue(index)
            management.archiveSession(index)
            management.archiveSession(index)
            assertEquals(emptyList(), management.getSessionCatalog(false))
            val archived = management.getSessionCatalog(true).single()
            assertTrue(archived.archived)
            assertTrue(archived.isActive)
            assertSame(owner, repository.openSessions.value.getValue(index))
            management.unarchiveSession(index)
            management.unarchiveSession(index)
            assertFalse(management.getSessionCatalog(false).single().archived)
            assertFailsWith<SessionNotFound> { management.archiveSession(index + 999) }
            assertFailsWith<SessionNotFound> { management.unarchiveSession(index + 999) }
        }
    }

    test("history fork preserves source and inherited identity but appends a fresh current turn") {
        runtimeFixture { repository, index, runtime ->
            val management = testSessionManagement(repository, { error("Fork must not read global defaults") })
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("keep")))
            val boundarySettings = repository.openSessions.value.getValue(index).storage.getSettingsAt(1)
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("remove")))
            val source = repository.openSessions.value.getValue(index).storage as ObservableKodexAgentStorage
            val nonce = source.cacheNonce.value
            val fork = management.forkSessionHistory(index, 2, nonce)
            assertFalse(management.getSessionCatalog(false).single { it.sessionIndex == fork }.isActive)
            management.keepSessionAlive(fork)
            val target = repository.openSessions.value.getValue(fork).storage
            assertEquals(
                source.getCommitExact(1, AgentCommitType.IndexTypes)?.commit,
                target.getCommitExact(1, AgentCommitType.IndexTypes)?.commit,
            )
            assertNull(target.getCommitExact(2, AgentCommitType.IndexTypes))
            assertEquals(boundarySettings, target.getSettingsAt(1))
            val current = target.getSettingsAt(target.latestIndex())
            assertNotEquals(boundarySettings.turnId, current.turnId)
            assertNull(current.turnState)
            assertEquals(boundarySettings.windowId, current.windowId)
            assertEquals("[fork] ${boundarySettings.threadName}", current.threadName)
            assertEquals(2, runtime.getLatestIndex(index))
            assertEquals(nonce, source.cacheNonce.value)
        }
    }

    test("history admission rejects stale or invalid boundaries without creating targets") {
        runtimeFixture { repository, index, _ ->
            val management = testSessionManagement(repository)
            val nonce = BackendAgentStorageRpc(repository).getCacheNonce(index)
            assertFailsWith<CacheNonceMismatch> { management.forkSessionHistory(index, 1, nonce xor 1) }
            assertFailsWith<IllegalArgumentException> { management.forkSessionHistory(index, 0, nonce) }
            assertFailsWith<IllegalArgumentException> { management.forkSessionHistory(index, 2, nonce) }
            assertEquals(listOf(index), repository.entries.value)
            repository.closeSession(index)
            assertFailsWith<SessionNotActive> { management.forkSessionHistory(index, 1, nonce) }
            assertFailsWith<SessionNotFound> { management.forkSession(index + 999) }
        }
    }

    test("complete fork temporarily opens an inactive source and leaves both entries closed") {
        runtimeFixture { repository, index, runtime ->
            val management = testSessionManagement(repository, { error("Fork must not read global defaults") })
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("history")))
            management.archiveSession(index)
            repository.closeSession(index)
            val fork = management.forkSession(index)
            val entries = management.getSessionCatalog(true)
            assertEquals(2, entries.size)
            assertTrue(entries.none { it.isActive })
            assertTrue(entries.single { it.sessionIndex == index }.archived)
            assertFalse(entries.single { it.sessionIndex == fork }.archived)
            management.keepSessionAlive(fork)
            assertNotNull(repository.openSessions.value.getValue(fork).storage.getCommitExact(1, AgentCommitType.IndexTypes))
        }
    }

    test("running source cannot fork and catalog running is independent of frontends") {
        val entered = CompletableDeferred<Unit>()
        runtimeFixture(
            mockOpenAiClient {
                createResponse {
                    flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
        ) { repository, index, runtime ->
            val management = testSessionManagement(repository)
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("run")))
            val waiter = async { runtime.resume(index) }
            entered.await()
            waiter.cancelAndJoin()
            assertTrue(management.getSessionCatalog(false).single().running)
            assertFailsWith<IllegalArgumentException> { management.forkSession(index) }
            assertTrue(management.deleteSession(index))
            assertEquals(emptyList(), management.getSessionCatalog(false))
            assertFalse(management.deleteSession(index))
            assertEquals(index, management.createSession(Path(".")))
        }
    }

    test("created session remains when later submission fails") {
        runtimeFixture { repository, _, runtime ->
            val management = testSessionManagement(repository)
            val created = management.createSession(Path("."))
            assertFailsWith<IllegalStateException> {
                error("separate submission failed")
            }
            assertTrue(management.getSessionCatalog(false).any { it.sessionIndex == created })
            assertEquals(1, runtime.appendUserMessage(created, listOf(ContentItem.InputText("retry draft"))))
        }
    }

    test("creation failure cleans its reserved entry without inventing a result") {
        coroutineScope {
            val original = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            try {
                val failingRepository = object : KodexSessionRepository by original {
                    override suspend fun open(entryIndex: Int): KodexAgentSession = error("load failed")
                }
                val management = testSessionManagement(failingRepository)
                val failure = assertFailsWith<IllegalStateException> {
                    management.createSession(Path("."))
                }
                assertEquals("load failed", failure.message)
                assertTrue(original.entries.value.isEmpty())
            } finally {
                original.cancelAndJoin()
            }
        }
    }

    test("cancelling the creation waiter does not revoke accepted initialization") {
        coroutineScope {
            val opened = CompletableDeferred<KodexAgentSession>()
            val release = CompletableDeferred<Unit>()
            val original = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            try {
                val delayedRepository = object : KodexSessionRepository by original {
                    override suspend fun open(entryIndex: Int): KodexAgentSession {
                        val session = original.open(entryIndex)
                        opened.complete(session)
                        release.await()
                        return session
                    }
                }
                var defaults = testNewSessionDefaults()
                val captured = defaults
                var captures = 0
                val management = testSessionManagement(delayedRepository, { captures++; defaults })
                val waiter = async { management.createSession(Path(".")) }
                val session = opened.await()
                defaults = defaults.copy(
                    model = OpenAiModelId("changed-during-initialization"),
                    reasoningEffort = ReasoningEffort.High,
                    serviceTier = ServiceTier.Ultrafast,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                )
                waiter.cancelAndJoin()
                release.complete(Unit)
                session.runtime.latestIndex.first { it >= 0 }
                val initialized = session.storage.getSettingsAt(0)
                assertEquals("Session 0", initialized.threadName)
                assertEquals(1, captures)
                assertEquals(captured.model, initialized.model)
                assertEquals(captured.reasoningEffort, initialized.reasoning.effort)
                assertEquals(captured.serviceTier, initialized.serviceTier)
                assertEquals(captured.requestUserInputMode, initialized.requestUserInputMode)
                assertEquals(1, original.entries.value.size)
            } finally {
                release.complete(Unit)
                original.cancelAndJoin()
            }
        }
    }
}

private fun testSessionManagement(
    repository: KodexSessionRepository,
    newSessionDefaults: () -> KodexNewSessionSettings = ::testNewSessionDefaults,
    titleSettings: () -> SessionTitleSettings = { SessionTitleSettings(enabled = false) },
    titleGenerator: SessionTitleGenerator = SessionTitleGenerator { _, _, _ -> error("unused") },
    reportBackgroundFailure: (Throwable) -> Unit = { throw it },
): BackendSessionManagement =
    BackendSessionManagement(
        repository = repository,
        newSessionDefaults = newSessionDefaults,
        titleSettings = titleSettings,
        titleGenerator = titleGenerator,
        reportBackgroundFailure = reportBackgroundFailure,
    )

private fun testNewSessionDefaults(): KodexNewSessionSettings =
    KodexNewSessionSettings(model = OpenAiModelId("test-model"))
