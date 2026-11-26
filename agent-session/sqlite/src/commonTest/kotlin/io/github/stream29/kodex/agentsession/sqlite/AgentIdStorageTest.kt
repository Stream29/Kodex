package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.agentsession.contract.StoredAgent
import io.github.stream29.kodex.agentsession.contract.AgentForkSourceChanged
import io.github.stream29.kodex.agentsession.contract.RunningSubagentsPreventDeletion
import io.github.stream29.kodex.agentruntime.contract.submitUserMessage
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import io.github.stream29.kodex.utils.sqlite.SqliteFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val fs = SystemCoroutineFileSystem

val agentIdStorageTest by testSuite(compartment = { TestCompartment.RealTime }) {
    testFixture {
        val home = Path(SystemTemporaryDirectory, "kodex-agent-id-${Random.nextLong().toULong()}")
        fs.createDirectories(home, mustCreate = true)
        home
    } closeWith { deleteTree(this) } asParameterForEach {
        test("concurrent Agent opens share an owner; root close and running-child delete do not stop it") { home ->
            val running = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { flow { running.complete(Unit); awaitCancellation() } }
            }
            testKodexAgentDependencies(client).use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    val sessionId = repository.create()
                    val root = repository.open(sessionId)
                    val rootId = checkNotNull(root.runtime.agentId)
                    val childId = repository.createChild(rootId).agentId
                    val owners = coroutineScope { List(4) { async { repository.openAgent(childId) } }.awaitAll() }
                    val child = checkNotNull(owners.first())
                    owners.forEach { assertSame(child, it) }
                    assertEquals(sessionId, child.sessionId)
                    assertSame(root, repository.openAgent(rootId))
                    child.runtime.modify { it.initialize(settings("child")) }
                    child.runtime.submitUserMessage(listOf(ContentItem.InputText("Keep this child running")))
                    withTimeout(5.seconds) { running.await() }
                    val turn = checkNotNull(child.runtime.runningTurn.value)
                    assertFailsWith<RunningSubagentsPreventDeletion> { repository.delete(sessionId) }
                    assertEquals(childId, repository.getAgentRecord(childId)?.agentId)
                    repository.closeSession(sessionId)
                    assertSame(child, repository.openSessions.value[childId])
                    assertTrue(turn.isActive)
                    turn.cancelAndJoin()
                    repository.delete(sessionId)
                    assertNull(repository.openAgent(childId))
                    assertTrue(repository.openSessions.value.isEmpty())
                }
            }
        }
        test("root lookup, fork, archive and child relations are independent of same-numbered Sessions") { home ->
            val dbPath = Path(home, "kodex.db")
            openSqliteConnection(dbPath).use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions(session_id) VALUES(12), (90)")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES(90, 12), (12, 90)")
                db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(12), (90)")
                for (includeArchived in listOf(false, true)) {
                    val plan = db.query("EXPLAIN QUERY PLAN ${rootCatalogQuery(includeArchived)}").toString()
                    assertTrue("agent_commits" !in plan && "commits_global_index" !in plan, plan)
                }
            }
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    assertEquals(90, repository.getRootAgentId(12))
                    assertEquals(12, repository.getRootAgentId(90))
                    assertTrue(repository.openSessions.value.isEmpty())
                    assertFailsWith<IllegalArgumentException> { repository.getRootAgentId(91) }
                    val root = repository.open(12)
                    assertEquals(90, root.runtime.agentId)
                    root.runtime.modify { it.initialize(settings("real root")) }
                    repository.open(90).runtime.modify { it.initialize(settings("unrelated root")) }
                    val child = repository.createChild(90)
                    val grandchild = repository.createChild(child.agentId)
                    assertEquals(StoredAgent(91, 12, 90, false), child)
                    assertEquals(StoredAgent(92, 12, 91, false), grandchild)
                    assertNull(repository.markChildCompleted(90))
                    assertEquals(91, repository.markChildCompleted(92))
                    assertNull(repository.markChildCompleted(92))
                    assertEquals(grandchild.copy(completed = true), repository.getAgentRecord(92))
                    assertFalse(repository.discardUninitializedChild(90))
                    assertFalse(repository.discardUninitializedChild(91))
                    assertFalse(repository.discardUninitializedChild(92))
                    val transient = repository.createChild(90)
                    assertTrue(repository.discardUninitializedChild(transient.agentId))
                    assertNull(repository.getAgentRecord(transient.agentId))
                    assertFailsWith<IllegalArgumentException> { repository.createChild(transient.agentId) }

                    val creation = StableSubagentCreatedEvent(child.agentId)
                    val completion = StableSubagentCompletedEvent(child.agentId)
                    val compaction = StableContextCompaction(encryptedContent = "fixture", retainedItems = listOf(creation))
                    val (before, spawnAt, packedAt, completedAt, after) = root.runtime.modify { storage ->
                        listOf(
                            storage.commit(StableUserMessage(listOf(ContentItem.InputText("before")))),
                            storage.commit(creation),
                            storage.commit(compaction),
                            storage.commit(completion),
                            storage.commit(StableUserMessage(listOf(ContentItem.InputText("after")))),
                        )
                    }
                    val forkId = repository.createFork(12)
                    val fork = repository.open(forkId)
                    assertEquals(94, fork.runtime.agentId)
                    assertEquals("real root", fork.storage.getSettingsAt(0).name)
                    assertNull(fork.storage.getCommitExact(spawnAt, AgentCommitType.IndexTypes))
                    assertEquals(creation, root.storage.getCommitExact(spawnAt, AgentCommitType.IndexTypes)?.commit)
                    for (retained in listOf(before, packedAt, completedAt, after)) {
                        val allTypes = AgentCommitType.IndexTypes + AgentCommitType.WorkTypes
                        val source = root.storage.getCommitExact(retained, allTypes)
                        assertTrue(source != null)
                        assertEquals(source.commit, fork.storage.getCommitExact(retained, allTypes)?.commit)
                    }
                    assertEquals(compaction, fork.storage.getCommitExact(packedAt, AgentCommitType.IndexTypes)?.commit)
                    assertEquals(listOf(94, 90, 12), repository.listEntries().map { it.rootAgentId })
                    repository.getEntry(12).archive()
                    assertTrue(repository.getEntry(12).archived)
                    assertFalse(repository.getEntry(90).archived)
                    repository.closeAndJoin()
                    assertFalse(fs.exists(Path(home, "sessions")))
                    openSqliteConnection(dbPath).use { db ->
                        assertEquals(listOf(listOf(94L)), db.query("SELECT agent_id FROM agents WHERE session_id = ?", listOf(forkId.toLong())))
                    }
                }
                assertFalse(fs.exists(Path(home, "sessions")))
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    assertEquals(90, repository.getEntry(12).rootAgentId)
                    assertEquals("real root", repository.getEntry(12).name)
                    assertEquals("unrelated root", repository.getEntry(90).name)
                    repository.delete(12)
                    assertNull(repository.getAgentRecord(90))
                    assertNull(repository.getAgentRecord(91))
                    assertNull(repository.getAgentRecord(92))
                    assertEquals(12, repository.getRootAgentId(90))
                    val created = repository.create()
                    assertEquals(1, created)
                    assertEquals(95, repository.getRootAgentId(created))
                }
                assertFalse(fs.exists(Path(home, "sessions")))
            }
        }

        test("fork copy accepts a sparse exclusive boundary and rejects a replaced owner") { home ->
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    val sessionId = repository.create()
                    val source = repository.open(sessionId)
                    val agentId = repository.getRootAgentId(sessionId)
                    source.runtime.modify {
                        it.initialize(settings("initial settings"))
                        it.commit(settings("beyond the boundary"))
                    }
                    repository.closeSession(sessionId)
                    openSqliteConnection(Path(home, "kodex.db")).use { db ->
                        db.execute(
                            """UPDATE agent_commits SET "index" = 5 WHERE agent_id = ? AND "index" = 1""",
                            listOf(agentId.toLong()),
                        )
                    }
                    val reopened = repository.open(sessionId)
                    assertFailsWith<AgentForkSourceChanged> { repository.createAgentFork(agentId, source, 4) }
                    assertEquals(listOf(sessionId), repository.list())
                    val fork = reopened.runtime.modify { checkNotNull(repository.createAgentFork(agentId, reopened, 4)) }
                    assertEquals("initial settings", fork.boundarySettings.name)
                    val target = repository.open(fork.sessionId).storage
                    assertEquals(0, target.latestIndex())
                    assertEquals("initial settings", target.getSettingsAt(0).name)
                    assertEquals("beyond the boundary", reopened.storage.getSettingsAt(5).name)
                }
            }
        }
    }
}

private fun settings(name: String) = KodexAgentSettings(model = OpenAiModelId("test"), name = name)

private suspend fun deleteTree(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { deleteTree(it) }
    fs.delete(path, mustExist = false)
}
