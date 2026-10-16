package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.rpcexception.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import kotlin.time.Instant

val backendSessionManagementTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("catalog preserves missing dates and does not activate entries") {
        runtimeFixture { host, initial, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            assertTrue(management.deleteSession(initial))
            assertEquals(emptyList(), management.getSessionCatalog(false))
            val index = host.repository().create()
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

    test("creation generates the default title and distinct current identity without submitting") {
        runtimeFixture { host, _, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            val settings = KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "ignored")
            val first = management.createSession(settings)
            val second = management.createSession(settings)
            val a = host.session(first).session.storage.settings[0]
            val b = host.session(second).session.storage.settings[0]
            assertEquals("Session $first", a.threadName)
            assertNotEquals(a.turnId, b.turnId)
            assertNotEquals(a.windowId, b.windowId)
            assertEquals(0, runtime.getLatestIndex(first))
            assertEquals(-1, host.session(first).session.storage.index.latestIndex())
            val row = management.getSessionCatalog(false).single { it.sessionIndex == first }
            assertNotNull(row.createdAt)
            assertEquals(row.createdAt, row.updatedAt)
            assertTrue(row.isActive)
            assertFalse(row.running)
        }
    }

    test("catalog ordering and timestamp zero stay distinct from later timestamps") {
        runtimeFixture { host, _, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            val a = host.repository().create()
            val b = host.repository().create()
            val timestamp = Instant.parse("2099-01-01T00:00:00Z")
            for (index in listOf(a, b)) {
                val temporary = host.repository().open(index)
                try {
                    temporary.runtime.modify {
                        it.settings[3] = KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "late")
                        it.timestamp[3] = timestamp
                    }
                } finally { temporary.cancelAndJoin() }
            }
            val entries = management.getSessionCatalog(false)
            assertEquals(listOf(b, a), entries.take(2).map { it.sessionIndex })
            entries.take(2).forEach {
                assertNull(it.createdAt)
                assertEquals(timestamp, it.updatedAt)
                assertFalse(it.isActive)
            }
        }
    }

    test("archive is idempotent and changes neither owner nor execution") {
        runtimeFixture { host, index, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            val owner = host.session(index)
            management.archiveSession(index)
            management.archiveSession(index)
            assertEquals(emptyList(), management.getSessionCatalog(false))
            val archived = management.getSessionCatalog(true).single()
            assertTrue(archived.archived)
            assertTrue(archived.isActive)
            assertSame(owner, host.session(index))
            management.unarchiveSession(index)
            management.unarchiveSession(index)
            assertFalse(management.getSessionCatalog(false).single().archived)
            assertFailsWith<SessionNotFound> { management.archiveSession(index + 999) }
            assertFailsWith<SessionNotFound> { management.unarchiveSession(index + 999) }
        }
    }

    test("history fork preserves source and inherited identity but appends a fresh current turn") {
        runtimeFixture { host, index, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("keep")))
            val boundarySettings = host.session(index).session.storage.settings[1]
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("remove")))
            val source = host.session(index).session.storage as ObservableKodexAgentStorage
            val nonce = source.index.cacheNonce.value
            val fork = management.forkSessionHistory(index, 2, nonce)
            assertFalse(management.getSessionCatalog(false).single { it.sessionIndex == fork }.isActive)
            management.keepSessionAlive(fork)
            val target = host.session(fork).session.storage
            assertEquals(source.index.getExact(1), target.index.getExact(1))
            assertNull(target.index.getExact(2))
            assertEquals(boundarySettings, target.settings[1])
            val current = target.settings[target.latestIndex()]
            assertNotEquals(boundarySettings.turnId, current.turnId)
            assertNull(current.turnState)
            assertEquals(boundarySettings.windowId, current.windowId)
            assertEquals("[fork] ${boundarySettings.threadName}", current.threadName)
            assertEquals(2, runtime.getLatestIndex(index))
            assertEquals(nonce, source.index.cacheNonce.value)
        }
    }

    test("history admission rejects stale or invalid boundaries without creating targets") {
        runtimeFixture { host, index, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            val nonce = BackendIndexTimelineRpc(host).getCacheNonce(index)
            assertFailsWith<CacheNonceMismatch> { management.forkSessionHistory(index, 1, nonce xor 1) }
            assertFailsWith<IllegalArgumentException> { management.forkSessionHistory(index, 0, nonce) }
            assertFailsWith<IllegalArgumentException> { management.forkSessionHistory(index, 2, nonce) }
            assertEquals(listOf(index), host.repository().entries.value)
            host.session(index).session.cancelAndJoin()
            assertFailsWith<SessionNotActive> { management.forkSessionHistory(index, 1, nonce) }
            assertFailsWith<SessionNotFound> { management.forkSession(index + 999) }
        }
    }

    test("complete fork temporarily opens an inactive source and leaves both entries closed") {
        runtimeFixture { host, index, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("history")))
            management.archiveSession(index)
            host.session(index).session.cancelAndJoin()
            val fork = management.forkSession(index)
            val entries = management.getSessionCatalog(true)
            assertEquals(2, entries.size)
            assertTrue(entries.none { it.isActive })
            assertTrue(entries.single { it.sessionIndex == index }.archived)
            assertFalse(entries.single { it.sessionIndex == fork }.archived)
            management.keepSessionAlive(fork)
            assertNotNull(host.session(fork).session.storage.index.getExact(1))
        }
    }

    test("running source cannot fork and catalog running is independent of frontends") {
        val entered = CompletableDeferred<Unit>()
        runtimeFixture(mockOpenAiClient { createResponse { flow {
            entered.complete(Unit)
            awaitCancellation()
        } } }) { host, index, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            runtime.appendUserMessage(index, listOf(ContentItem.InputText("run")))
            val waiter = async { runtime.resume(index) }
            entered.await()
            waiter.cancelAndJoin()
            assertTrue(management.getSessionCatalog(false).single().running)
            assertFailsWith<IllegalArgumentException> { management.forkSession(index) }
            assertTrue(management.deleteSession(index))
            assertEquals(emptyList(), management.getSessionCatalog(false))
            assertFalse(management.deleteSession(index))
            assertEquals(index, management.createSession(KodexAgentSettings(OpenAiModelId("test-model"))))
        }
    }

    test("suggested batch returns ordered metadata and launches children without waiting for model completion") {
        val entered = MutableStateFlow(0)
        val release = CompletableDeferred<Unit>()
        val failures = mutableListOf<Throwable>()
        runtimeFixture(mockOpenAiClient { createResponse { flow {
            entered.update { it + 1 }
            release.await()
            emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
        } } }) { host, _, runtime ->
            val management = BackendSessionManagement(host, runtime) { failures += it }
            val tasks = listOf(SuggestedSubagentTask("one", "first"), SuggestedSubagentTask("two", "second"))
            val results = management.createSuggestedSessions(tasks, KodexAgentSettings(OpenAiModelId("test-model")))
            assertEquals(listOf("one", "two"), results.map { it.meta.name })
            entered.first { it == 2 }
            for (result in results) {
                assertEquals(runtime.getStorageUri(result.sessionIndex), result.meta.uri)
                assertTrue(runtime.getRunningTurn(result.sessionIndex))
                assertEquals(result.meta.name, host.session(result.sessionIndex).session.storage.settings[0].threadName)
            }
            release.complete(Unit)
            results.forEach { result -> runtime.getRunningTurnFlow(result.sessionIndex).first { !it } }
            assertTrue(failures.isEmpty())
        }
    }

    test("created session remains when later submission fails") {
        runtimeFixture { host, _, runtime ->
            val management = BackendSessionManagement(host, runtime) { throw it }
            val created = management.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
            assertFailsWith<IllegalStateException> {
                host.inSession(created) { error("separate submission failed") }
            }
            assertTrue(management.getSessionCatalog(false).any { it.sessionIndex == created })
            assertEquals(1, runtime.appendUserMessage(created, listOf(ContentItem.InputText("retry draft"))))
        }
    }

    test("creation failure cleans its reserved entry without inventing a result") {
        withBackendSessionHost({
            val original = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            object : KodexRootSessionRepository by original {
                override suspend fun open(entryIndex: Int): KodexAgentSession = error("load failed")
            }
        }) { host ->
            val runtime = BackendAgentRuntimeRpc(host, { SessionTitleSettings(false) }, SessionTitleGenerator { _, _, _ -> error("unused") })
            val management = BackendSessionManagement(host, runtime) { throw it }
            val failure = assertFailsWith<IllegalStateException> {
                management.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
            }
            assertEquals("load failed", failure.message)
            assertTrue(host.repository().entries.value.isEmpty())
        }
    }

    test("cancelling the creation waiter does not revoke accepted initialization") {
        val opened = CompletableDeferred<KodexAgentSession>()
        val release = CompletableDeferred<Unit>()
        withBackendSessionHost({
            val original = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            object : KodexRootSessionRepository by original {
                override suspend fun open(entryIndex: Int): KodexAgentSession {
                    val session = original.open(entryIndex)
                    opened.complete(session)
                    release.await()
                    return session
                }
            }
        }) { host ->
            val runtime = BackendAgentRuntimeRpc(host, { SessionTitleSettings(false) }, SessionTitleGenerator { _, _, _ -> error("unused") })
            val management = BackendSessionManagement(host, runtime) { throw it }
            val waiter = async { management.createSession(KodexAgentSettings(OpenAiModelId("test-model"))) }
            val session = opened.await()
            waiter.cancelAndJoin()
            release.complete(Unit)
            session.runtime.latestIndex.first { it >= 0 }
            assertEquals("Session 0", session.storage.settings[0].threadName)
            assertEquals(1, host.repository().entries.value.size)
        }
    }
}
