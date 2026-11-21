@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.AgentForkCopy
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ext.appendCompaction
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnSource
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SessionSpawnRejection
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.time.Instant

val backendSessionManagementTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("spawn inherits only configuration from a matching live owner; rejects stale and forged sources") {
        runtimeFixture { repository, _, runtime ->
            val management = testSessionManagement(repository)
            val directory = SystemCoroutineFileSystem.resolve(SystemTemporaryDirectory)
            val source = assertIs<SessionSpawnResult.Created>(management.spawnSession(SessionSpawnRequest(
                launchDirectory = directory.toString(), model = OpenAiModelId("caller"), reasoningEffort = ReasoningEffort.High,
                serviceTier = ServiceTier.Flex, requestUserInputMode = RequestUserInputMode.NoQuestion,
            )) { emptyList() })
            val caller = repository.openSessions.value.getValue(source.rootAgentId)
            val previous = caller.storage.getSettingsAt(0)
            caller.runtime.appendUserMessage(listOf(ContentItem.InputText("caller-only history")))
            val copied = assertIs<SessionSpawnResult.Created>(management.spawnSession(SessionSpawnRequest(
                launchDirectory = "/not-used",
                source = SessionSpawnSource(source.rootAgentId, source.invocationId),
                serviceTier = ServiceTier.Default,
            )) { emptyList() })
            val fresh = repository.openSessions.value.getValue(copied.rootAgentId).storage.getSettingsAt(0)
            assertEquals(previous.model, fresh.model)
            assertEquals(previous.reasoning.effort, fresh.reasoning.effort)
            assertEquals(previous.requestUserInputMode, fresh.requestUserInputMode)
            assertEquals(directory, fresh.cwd)
            assertEquals(ServiceTier.Default, fresh.serviceTier)
            assertEquals("Session ${copied.sessionId}", fresh.name)
            assertNotEquals(previous.windowId, fresh.windowId)
            assertNotEquals(previous.turnId, fresh.turnId)
            assertEquals(0, runtime.getLatestIndex(copied.rootAgentId))
            assertFailsWith<AgentNotActive> {
                runtime.submit(copied.rootAgentId, listOf(ContentItem.InputText("wrong owner")), source.invocationId)
            }
            assertEquals(0, runtime.getLatestIndex(copied.rootAgentId))
            val unchanged = repository.list()
            val request = SessionSpawnRequest(directory.toString(), source = SessionSpawnSource(source.rootAgentId, "other-backend"))
            assertEquals(SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidSource), management.spawnSession(request) { emptyList() })
            repository.closeSession(source.sessionId)
            repository.open(source.sessionId)
            assertEquals(SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidSource),
                management.spawnSession(request.copy(source = SessionSpawnSource(source.rootAgentId, source.invocationId))) { emptyList() })
            assertEquals(unchanged, repository.list())
            assertEquals(SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidDirectory),
                management.spawnSession(SessionSpawnRequest("relative-path")) { emptyList() })
            assertEquals(SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidConfiguration),
                management.spawnSession(SessionSpawnRequest(directory.toString(), model = OpenAiModelId("unavailable"))) {
                    listOf(ModelInfo(slug = OpenAiModelId("available"), displayName = "Fixture"))
                })
        }
    }

    test("catalog preserves missing dates and does not activate entries") {
        runtimeFixture { repository, initial, runtime ->
            val management = testSessionManagement(repository)
            assertTrue(management.deleteSession(initial))
            assertEquals(emptyList(), management.getSessionCatalog(false))
            val index = repository.create()
            val row = management.getSessionCatalog(false).single()
            assertEquals(index, row.sessionIndex)
            assertNull(row.name)
            assertNull(row.createdAt)
            assertNull(row.updatedAt)
            assertFalse(row.isActive)
            assertFalse(row.running)
            assertFailsWith<AgentNotActive> { runtime.getLatestIndex(row.rootAgentId) }
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
            assertEquals("Session $first", a.name)
            assertEquals("Session $second", b.name)
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
                        it.commit(KodexAgentSettings(model = OpenAiModelId("test-model"), name = "init-without-timestamp"))
                        it.commit(
                            KodexAgentSettings(model = OpenAiModelId("test-model"), name = "late"),
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
            val agentId = repository.getRootAgentId(index)
            val session = repository.openSessions.value.getValue(agentId)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("keep")))
            val boundarySettings = session.storage.getSettingsAt(1)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("remove")))
            val source = session.storage as ObservableKodexAgentStorage
            val nonce = source.cacheNonce.value
            val fork = management.forkAgentHistory(agentId, 2, nonce)
            assertFalse(management.getSessionCatalog(false).single { it.sessionIndex == fork }.isActive)
            val target = repository.open(fork).storage
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
            assertEquals("[fork] ${boundarySettings.name}", current.name)
            assertEquals(2, runtime.getLatestIndex(agentId))
            assertEquals(nonce, source.cacheNonce.value)
        }
    }

    test("both Agent fork paths drop child creation rows including a source tail") {
        runtimeFixture { repository, index, _ ->
            val sqlite = repository as SqliteKodexSessionRepository
            val management = testSessionManagement(repository)
            val session = sqlite.open(index)
            val agentId = sqlite.getRootAgentId(index)
            val child = sqlite.createChild(agentId)
            val notice = StableSubagentCreatedEvent(child.agentId)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("before")))
            val inside = session.runtime.modify { it.commit(notice) }
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("after")))
            val tail = session.runtime.modify { it.commit(notice) }
            val nonce = session.storage.cacheNonce.value
            for (fork in listOf(management.forkAgent(agentId), management.forkAgentHistory(agentId, tail + 1, nonce))) {
                val target = sqlite.open(fork).storage
                assertNull(target.getCommitExact(inside, AgentCommitType.IndexTypes))
                assertNull(target.getCommitExact(tail, AgentCommitType.IndexTypes))
                for (retained in listOf(inside - 1, tail - 1)) {
                    val message = session.storage.getCommitExact(retained, AgentCommitType.IndexTypes)
                    assertNotNull(message)
                    assertEquals(message.commit, target.getCommitExact(retained, AgentCommitType.IndexTypes)?.commit)
                }
                assertTrue(target.getSettingsAt(target.latestIndex()).name.startsWith("[fork]"))
            }
            assertEquals(notice, session.storage.getCommitExact(inside, AgentCommitType.IndexTypes)?.commit)
            assertEquals(notice, session.storage.getCommitExact(tail, AgentCommitType.IndexTypes)?.commit)
            assertEquals(child, sqlite.getAgentRecord(child.agentId))
            assertEquals(nonce, session.storage.cacheNonce.value)
        }
    }

    test("compacted fork keeps only the selected window and can manually resume after reopening") {
        val requests = mutableListOf<ResponsesApiRequest>()
        val client = mockOpenAiClient {
            createResponse { request ->
                requests += request
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(
                        0, ResponseItem.Message(role = MessageRole.Assistant, content = listOf(ContentItem.OutputText("continued"))),
                    ),
                    ResponsesStreamEvent.Completed(Response(id = "fork-finish", endTurn = true)),
                )
            }
            createRemoteCompactionV2Response {
                RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "recompacted"), null)
            }
        }
        runtimeFixture(client) { repository, index, _ ->
            val management = testSessionManagement(repository)
            val source = repository.open(index)
            val agentId = repository.getRootAgentId(index)
            val dropped = source.runtime.appendUserMessage(listOf(ContentItem.InputText("old raw message")))
            val ask = source.runtime.createAskUser(
                RequestUserInputArgs(listOf(RequestUserInputQuestion(id = "question", header = "H", question = "Continue?"))),
            )
            val first = StableContextCompaction(encryptedContent = "first-window")
            val second = StableContextCompaction(encryptedContent = "future-window")
            val (one, two) = source.runtime.modify {
                val one = it.appendCompaction(first, Instant.DISTANT_PAST, "window-1", it.getSettingsAt(it.latestIndex()))
                val two = it.appendCompaction(second, Instant.DISTANT_PAST, "window-2", it.getSettingsAt(one))
                one to two
            }
            val nonce = source.storage.cacheNonce.value
            val before = source.storage.getCommitsIn(0..two)

            val originalPrefix = repository.open(management.forkAgentHistory(agentId, one, nonce))
            assertNotNull(originalPrefix.storage.getCommitExact(dropped, AgentCommitType.IndexTypes))
            assertNull(originalPrefix.storage.getCommitExact(one, AgentCommitType.IndexTypes))
            val latest = repository.open(management.forkAgent(agentId))
            assertNull(latest.storage.getCommitExact(one, AgentCommitType.IndexTypes))
            assertEquals(second, latest.storage.getCommitExact(two, AgentCommitType.IndexTypes)?.commit)
            assertEquals(listOf(ask), latest.storage.getAskUsersAt(two))
            assertEquals(KodexAgentStateValue.UserMessage, latest.runtime.state.value)

            val fork = management.forkAgentHistory(agentId, two, nonce)
            assertTrue(requests.isEmpty())
            val target = repository.open(fork)
            assertNull(target.storage.getCommitExact(dropped))
            assertEquals(first, target.storage.getCommitExact(one, AgentCommitType.IndexTypes)?.commit)
            assertNull(target.storage.getCommitExact(two, AgentCommitType.IndexTypes))
            assertEquals("window-1", target.storage.getSettingsAt(target.storage.latestIndex()).windowId)
            assertEquals(KodexAgentStateValue.UserMessage, target.runtime.state.value)
            assertEquals(listOf(ask), target.storage.getAskUsersAt(one))
            val forkId = repository.getRootAgentId(fork)
            val refork = repository.open(management.forkAgent(forkId))
            assertEquals(KodexAgentStateValue.UserMessage, refork.runtime.state.value)
            assertEquals(listOf(ask), refork.storage.getAskUsersAt(one))

            target.runtime.appendUserMessage(listOf(ContentItem.InputText("new branch message")))
            target.runtime.resume()
            assertEquals(KodexAgentStateValue.AssistantMessage, target.runtime.state.value)
            val compactedAgain = target.runtime.forcedCompact()
            val recompactedFork = repository.open(management.forkAgent(forkId))
            assertEquals(KodexAgentStateValue.AssistantMessage, recompactedFork.runtime.state.value)
            assertNull(recompactedFork.storage.getCommitExact(one, AgentCommitType.IndexTypes))
            assertEquals("recompacted", (recompactedFork.storage.getCommitExact(compactedAgain, AgentCommitType.IndexTypes)?.commit as StableContextCompaction).encryptedContent)
            repository.closeSession(fork)
            val reopened = repository.open(fork)
            reopened.runtime.modify { it.revert(one + 1) }
            assertEquals(KodexAgentStateValue.UserMessage, reopened.runtime.state.value)
            reopened.runtime.resume()
            for (request in requests) {
                assertTrue(request.input.any { it is ResponseItem.Compaction && it.encryptedContent == "first-window" })
                assertFalse(request.input.filterIsInstance<ResponseItem.Message>().any {
                    ContentItem.InputText("old raw message") in it.content
                })
            }
            assertEquals(2, requests.size)
            assertEquals(before, source.storage.getCommitsIn(0..two))
            assertEquals(nonce, source.storage.cacheNonce.value)
        }
    }

    test("history admission rejects stale or invalid boundaries without creating targets") {
        runtimeFixture { repository, index, runtime ->
            val management = testSessionManagement(repository)
            val agentId = repository.getRootAgentId(index)
            val nonce = runtime.getCacheNonce(agentId)
            assertFailsWith<CacheNonceMismatch> { management.forkAgentHistory(agentId, 1, nonce xor 1) }
            assertFailsWith<IllegalArgumentException> { management.forkAgentHistory(agentId, 0, nonce) }
            assertFailsWith<IllegalArgumentException> { management.forkAgentHistory(agentId, 2, nonce) }
            assertEquals(listOf(index), repository.entries.value)
            repository.closeSession(index)
            assertFailsWith<AgentNotActive> { management.forkAgentHistory(agentId, 1, nonce) }
            assertFailsWith<AgentNotFound> { management.forkAgent(agentId + 999) }
            assertFailsWith<AgentNotFound> { management.forkAgentHistory(agentId + 999, 1, nonce) }
            repository.open(index)
            assertFailsWith<CacheNonceMismatch> { management.forkAgentHistory(agentId, 1, nonce) }
            assertEquals(listOf(index), repository.entries.value)
        }
    }

    test("exact child fork ignores a same-numbered Session and does not clone any descendants") {
        runtimeFixture { repository, index, _ ->
            val sqlite = repository as SqliteKodexSessionRepository
            val management = testSessionManagement(repository)
            val root = repository.getRootAgentId(index)
            val child = sqlite.createChild(root)
            val sameNumberedSession = management.createSession(Path("."))
            assertEquals(child.agentId, sameNumberedSession)
            assertNotEquals(child.agentId, repository.getRootAgentId(sameNumberedSession))
            val grandchild = sqlite.createChild(child.agentId)
            val source = checkNotNull(sqlite.openAgent(child.agentId))
            val initial = KodexAgentSettings(model = OpenAiModelId("child-model"), name = "Child title", cwd = Path("/child"))
            source.runtime.modify { it.initialize(initial) }
            val childMessage = source.runtime.appendUserMessage(listOf(ContentItem.InputText("child history")))
            val childSpawn = source.runtime.modify { it.commit(StableSubagentCreatedEvent(grandchild.agentId)) }
            val before = source.storage.getCommitsIn(0..childSpawn)
            val nonce = source.storage.cacheNonce.value

            for (fork in listOf(management.forkAgent(child.agentId), management.forkAgentHistory(child.agentId, childSpawn, nonce))) {
                val targetId = repository.getRootAgentId(fork)
                val target = repository.open(fork).storage
                val record = assertNotNull(repository.getAgentRecord(targetId))
                assertEquals(fork, record.sessionId)
                assertNull(record.parentAgentId)
                assertFalse(record.completed)
                assertEquals(initial.model, target.getSettingsAt(0).model)
                assertEquals(initial.cwd, target.getSettingsAt(target.latestIndex()).cwd)
                assertEquals("[fork] Child title", target.getSettingsAt(target.latestIndex()).name)
                assertEquals(source.storage.getCommitExact(childMessage, AgentCommitType.IndexTypes)?.commit,
                    target.getCommitExact(childMessage, AgentCommitType.IndexTypes)?.commit)
                assertNull(target.getCommitExact(childSpawn, AgentCommitType.IndexTypes))
            }
            assertEquals(grandchild, repository.getAgentRecord(grandchild.agentId))
            assertEquals(before, source.storage.getCommitsIn(0..childSpawn))
            assertEquals(nonce, source.storage.cacheNonce.value)
        }
    }

    test("fork rejects an uninitialized exact child before allocating a Session") {
        runtimeFixture { repository, index, _ ->
            val management = testSessionManagement(repository)
            val child = (repository as SqliteKodexSessionRepository).createChild(repository.getRootAgentId(index))
            assertFailsWith<IllegalArgumentException> { management.forkAgent(child.agentId) }
            val active = checkNotNull(repository.openAgent(child.agentId))
            assertFailsWith<IllegalArgumentException> {
                management.forkAgentHistory(child.agentId, 1, active.storage.cacheNonce.value)
            }
            assertEquals(listOf(index), repository.list())
            assertEquals(-1, active.storage.latestIndex())
        }
    }

    test("fork cleans its target when opening it to update the title fails") {
        runtimeFixture { original, index, _ ->
            val agentId = original.getRootAgentId(index)
            val source = original.openSessions.value.getValue(agentId)
            val before = source.storage.getCommitsIn(0..source.storage.latestIndex())
            val failingRepository = object : KodexSessionRepository by original {
                override suspend fun open(entryIndex: Int): KodexAgentSession = error("Injected fork target open failure.")
            }
            val failure = assertFailsWith<IllegalStateException> { testSessionManagement(failingRepository).forkAgent(agentId) }
            assertEquals("Injected fork target open failure.", failure.message)
            assertEquals(listOf(index), original.list())
            assertSame(source, original.openSessions.value[agentId])
            assertEquals(before, source.storage.getCommitsIn(0..source.storage.latestIndex()))
        }
    }

    test("cancelling the fork waiter does not revoke accepted work") {
        runtimeFixture { original, index, _ ->
            val agentId = original.getRootAgentId(index)
            val copied = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayingRepository = object : KodexSessionRepository by original {
                override suspend fun createAgentFork(
                    sourceAgentId: Int,
                    expectedOwner: KodexAgentSession?,
                    untilExclusive: Int?,
                ): AgentForkCopy? {
                    val result = original.createAgentFork(sourceAgentId, expectedOwner, untilExclusive)
                    copied.complete(Unit)
                    release.await()
                    return result
                }
            }
            val management = testSessionManagement(delayingRepository)
            val caller = async { management.forkAgent(agentId) }
            try {
                copied.await()
                caller.cancelAndJoin()
                release.complete(Unit)
                val target = management.getSessionCatalog(false).single { it.sessionIndex != index }
                assertEquals("[fork] Session $index", target.name)
                assertFalse(target.isActive)
            } finally {
                release.complete(Unit)
            }
        }
    }

    test("current fork leaves an inactive archived source and its new Session closed") {
        runtimeFixture { repository, index, _ ->
            val management = testSessionManagement(repository, { error("Fork must not read global defaults") })
            val agentId = repository.getRootAgentId(index)
            repository.openSessions.value.getValue(agentId).runtime.appendUserMessage(listOf(ContentItem.InputText("history")))
            management.archiveSession(index)
            repository.closeSession(index)
            val fork = management.forkAgent(agentId)
            val entries = management.getSessionCatalog(true)
            assertEquals(2, entries.size)
            assertTrue(entries.none { it.isActive })
            assertTrue(entries.single { it.sessionIndex == index }.archived)
            assertFalse(entries.single { it.sessionIndex == fork }.archived)
            assertNotNull(repository.open(fork).storage.getCommitExact(1, AgentCommitType.IndexTypes))
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
            val agentId = repository.getRootAgentId(index)
            repository.openSessions.value.getValue(agentId).runtime.appendUserMessage(listOf(ContentItem.InputText("run")))
            val waiter = async { runtime.resume(agentId) }
            entered.await()
            waiter.cancelAndJoin()
            assertTrue(management.getSessionCatalog(false).single().running)
            assertFailsWith<IllegalArgumentException> { management.forkAgent(agentId) }
            assertFailsWith<IllegalArgumentException> { management.forkAgentHistory(agentId, 1, runtime.getCacheNonce(agentId)) }
            assertTrue(management.deleteSession(index))
            assertEquals(emptyList(), management.getSessionCatalog(false))
            assertFalse(management.deleteSession(index))
            assertEquals(index, management.createSession(Path(".")))
        }
    }

    test("created session remains when later submission fails") {
        runtimeFixture { repository, _, _ ->
            val management = testSessionManagement(repository)
            val created = management.createSession(Path("."))
            assertFailsWith<IllegalStateException> {
                error("separate submission failed")
            }
            assertTrue(management.getSessionCatalog(false).any { it.sessionIndex == created })
            assertEquals(1, repository.openSessions.value.getValue(created).runtime.appendUserMessage(listOf(ContentItem.InputText("retry draft"))))
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
                assertEquals("Session 0", initialized.name)
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
): BackendSessionManagement =
    BackendSessionManagement(
        repository = repository,
        newSessionDefaults = newSessionDefaults,
        titleSettings = titleSettings,
        titleGenerator = titleGenerator,
    )

private fun testNewSessionDefaults(): KodexNewSessionSettings =
    KodexNewSessionSettings(model = OpenAiModelId("test-model"))
