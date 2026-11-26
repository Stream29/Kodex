@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.sqlite.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.*
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val fs = SystemCoroutineFileSystem
private fun settings(name: String) = KodexAgentSettings(model = OpenAiModelId("test"), name = name)
private suspend fun removeTree(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { removeTree(it) }
    fs.delete(path, mustExist = false)
}
private suspend fun fixture(block: suspend CoroutineScope.(Path) -> Unit) {
    val root = Path(SystemTemporaryDirectory, "kodex-sqlite-a2-${Random.nextLong()}")
    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    try { scope.block(root) } finally { scope.coroutineContext[Job]?.cancelAndJoin(); removeTree(root) }
}
val sqliteRepositoryTest by testSuite {
    test("catalog writes explicitly invalidate only affected groups after commit") {
        fixture { root ->
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(root, dependencies).use { repo ->
                    assertEquals(0L, repo.sessionGroupsNonce.first())
                    val session = repo.create()
                    val id = repo.getRootAgentId(session)
                    assertEquals(listOf(null), repo.listSessionGroups().map { it.cwd })
                    val storage = repo.open(session).storage
                    val first = settings("original").copy(cwd = Path("/first"))
                    storage.commit(listOf(first), Instant.fromEpochSeconds(1))
                    assertEquals(listOf("/first"), repo.listSessionGroups().map { it.cwd })
                    val firstNonce = repo.sessionGroupNonce("/first").first()
                    val second = first.copy(name = "renamed", cwd = Path("/second"))
                    storage.commit(listOf(second), Instant.fromEpochSeconds(1, 1))
                    assertTrue(repo.sessionGroupsNonce.first() > firstNonce)
                    assertEquals(listOf("/second"), repo.listSessionGroups().map { it.cwd })
                    val page = repo.listSessionsInGroup("/second", false, 5)
                    assertEquals(listOf(session), page.entries.map { it.entryIndex })
                    assertEquals(listOf(id), page.entries.map { it.rootAgentId })

                    val beforeChild = repo.sessionGroupsNonce.first()
                    val child = repo.createChild(id)
                    repo.openAgent(child.agentId)!!.storage.commit(
                        listOf(settings("child").copy(cwd = Path("/child"))), Instant.fromEpochSeconds(2),
                    )
                    assertEquals(beforeChild, repo.sessionGroupsNonce.first())
                    assertEquals(listOf("/second"), repo.listSessionGroups().map { it.cwd })

                    storage.revert(2)
                    assertEquals(beforeChild, repo.sessionGroupsNonce.first(), "No-op revert must not invalidate groups.")
                    storage.revert(1)
                    assertEquals(listOf("/first"), repo.listSessionGroups().map { it.cwd })
                    val sourceBeforeFork = repo.listSessionGroups().single().groupNonce
                    val fork = repo.createFork(session)
                    assertTrue(repo.listSessionGroups().single().groupNonce > sourceBeforeFork)
                    assertEquals(2, repo.listSessionsInGroup("/first", false, 5).entries.size)
                    repo.getEntry(fork).archive()
                    repo.getEntry(session).archive()
                    val archived = repo.sessionGroupsNonce.first()
                    repo.getEntry(session).archive()
                    assertEquals(archived, repo.sessionGroupsNonce.first(), "No-op archive must not invalidate groups.")
                    assertTrue(repo.listSessionsInGroup("/first", false, 5).entries.isEmpty())
                    assertEquals(2, repo.listSessionsInGroup("/first", true, 5).entries.size)
                    repo.getEntry(session).unarchive()
                    assertEquals(listOf(session), repo.listSessionsInGroup("/first", false, 5).entries.map { it.entryIndex })
                    repo.delete(fork)
                    repo.delete(session)
                    assertTrue(repo.listSessionGroups().isEmpty())
                    assertEquals(0L, repo.listSessionsInGroup("/first", true, 5).groupNonce)
                }
            }
        }
    }
    test("SQLite catalog, fork, revert, archive and persistence ignore corrupted and deleted mirrors") {
        fixture { root ->
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(root, dependencies).use { repository ->
                    assertTrue(repository.entries.value.isEmpty())
                    val first = repository.create()
                    assertEquals(0, first)
                    val firstEntry = repository.getEntry(first)
                    assertNull(firstEntry.name)
                    assertNotEquals(first, firstEntry.rootAgentId)
                    val session = repository.open(first)
                    assertEquals(firstEntry.rootAgentId, session.storage.agentId)
                    assertEquals(firstEntry.rootAgentId, session.runtime.agentId)
                    assertSame(session, repository.open(first))
                    session.runtime.modify { it.initialize(settings("original")) }
                    val created = repository.getEntry(first).createdAt
                    assertNotNull(created)
                    val appendedIndex = session.storage.commit(
                        listOf(
                            settings("new"),
                        ),
                        Instant.fromEpochSeconds(123, 456),
                    )
                    assertEquals(1, appendedIndex)
                    val observable = session.storage
                    assertEquals(1, observable.latestIndexFlow.value)
                    val nonce = observable.cacheNonce.value
                    val second = repository.createFork(first)
                    assertEquals(1, second)
                    val fork = repository.open(second)
                    assertNotEquals(session.storage.agentId, fork.storage.agentId)
                    assertEquals("new", repository.getEntry(second).name)
                    fork.storage.revert(1)
                    assertEquals("original", repository.getEntry(second).name)
                    assertEquals(created, repository.getEntry(second).lastActivityAt)
                    session.storage.revert(1)
                    assertNotEquals(nonce, observable.cacheNonce.value)
                    assertEquals(0, observable.latestIndexFlow.value)
                    session.storage.steer(listOf(StableUserMessage(listOf(ContentItem.InputText("steered")))))
                    assertEquals(
                        listOf(StableUserMessage(listOf(ContentItem.InputText("steered")))),
                        session.storage.consumeSteer(),
                    )
                    repository.getEntry(first).archive()
                    assertEquals(listOf(second), repository.listEntries(false).map { it.entryIndex })
                    assertEquals(listOf(first, second), repository.list())
                    repository.getEntry(first).unarchive()
                    repository.closeAndJoin()
                    assertFalse(fs.exists(Path(root, "sessions")))
                    SqliteKodexSessionRepository(root, dependencies).use { reopened ->
                        assertEquals(listOf(0, 1), reopened.list())
                        assertEquals("original", reopened.getEntry(0).name)
                        assertEquals(created, reopened.getEntry(1).createdAt)
                        assertEquals("original", reopened.open(1).storage.getSettingsAt(0).name)
                        reopened.delete(0)
                        assertEquals(listOf(1), reopened.entries.value)
                        assertEquals(0, reopened.create())
                        assertEquals(-1, reopened.open(0).storage.latestIndex())
                    }
                    assertFalse(fs.exists(Path(root, "sessions")))
                }
            }
        }
    }
    test("named terminal rows load as work without moving their unnamed old mirror") {
        fixture { root ->
            fs.createDirectories(root)
            val terminalJson = """{"type":"event_terminal_exited","session_id":37,"command":"./fixture","exit_code":1}"""
            val namedJson = """{"type":"event_terminal_exited","session_id":37,"name":"Run fixture","command":"./fixture","exit_code":1}"""
            val terminal = StableTerminalExitedEvent(37, name = "Run fixture", command = "./fixture", exitCode = 1)
            val oldIndex = Path(root, "sessions", "0", "index")
            fs.createDirectories(oldIndex)
            fs.writeString(Path(oldIndex, "0.json"), terminalJson)
            fs.writeString(Path(oldIndex, "latest.json"), "0")
            openSqliteConnection(Path(root, "kodex.db")).use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions VALUES(0)")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES(0,0)")
                db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(0)")
                db.execute(
                    """INSERT INTO agent_commits VALUES
                       (0,'settings',0,'{"model":"test","name":"older"}','2026-11-17T00:00:00Z'),
                       (0,'steer',0,'[$namedJson]','2026-11-17T00:00:00Z'),
                       (0,'event_terminal_exited',0,'$namedJson','2026-11-17T00:00:00Z')""",
                )
                db.recomputeSessionCatalog(0)
                testKodexAgentDependencies().use { dependencies ->
                    SqliteKodexSessionRepository(root, dependencies).use { repository ->
                        val storage = repository.open(0).storage
                        assertEquals(OpenAiModelId("test"), storage.getSettingsAt(0).model)
                        assertEquals("older", storage.getSettingsAt(0).name)
                        assertEquals(terminal, storage.getCommitExact(0, AgentCommitType.WorkTypes)?.commit)
                        assertNull(storage.getCommitExact(0, AgentCommitType.IndexTypes))
                        assertEquals(listOf(terminal), storage.consumeSteer())
                        assertEquals(emptyList(), storage.getSteerAt(1))
                        val fork = repository.createFork(0)
                        assertEquals("older", repository.getEntry(fork).name)
                        assertEquals(terminal, repository.open(fork).storage.getCommitExact(0, AgentCommitType.WorkTypes)?.commit)
                        repository.closeAndJoin()
                        assertFalse(fs.exists(Path(root, "sessions", "$fork")))
                    }
                }
                assertEquals(
                    listOf(listOf(namedJson)),
                    db.query("""SELECT payload_json FROM agent_commits WHERE agent_id = 0 AND type = 'event_terminal_exited' AND "index" = 0"""),
                )
            }
            assertEquals(terminalJson, fs.readString(Path(oldIndex, "0.json")))
            assertFalse(fs.exists(Path(root, "sessions", "0", "work")))
        }
    }

    test("rollback leaves cache flows and mirror untouched") {
        fixture { root ->
            fs.createDirectories(root)
            openSqliteConnection(Path(root, "kodex.db")).use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions VALUES(0)")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES(0,0)")
                db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(0)")
                testKodexAgentDependencies().use { dependencies ->
                    SqliteKodexSessionRepository(root, dependencies).use { repository ->
                        val session = repository.open(0)
                        val observable = session.storage
                        val nonce = observable.cacheNonce.value
                        db.execute("""CREATE TRIGGER reject_init BEFORE INSERT ON agent_commits WHEN NEW.type = 'llm_request_meta_initialization' AND NEW."index" = 0 BEGIN SELECT RAISE(ABORT, 'injected'); END""")
                        assertFailsWith<SqliteFailure> { session.runtime.modify { it.initialize(settings("failed")) } }
                        assertEquals(-1, observable.latestIndexFlow.value)
                        assertEquals(nonce, observable.cacheNonce.value)
                        assertEquals(-1, session.storage.latestIndex())
                        assertNull(repository.getEntry(0).name)
                        db.execute("DROP TRIGGER reject_init")
                        session.runtime.modify { it.initialize(settings("committed")) }
                        assertEquals(0, observable.latestIndexFlow.value)
                        db.execute("""CREATE TRIGGER reject_settings_update BEFORE INSERT ON agent_commits WHEN NEW.type = 'settings' AND NEW."index" > 0 BEGIN SELECT RAISE(ABORT, 'injected'); END""")
                        assertFailsWith<SqliteFailure> { session.runtime.updateSettings(settings("uncommitted")) }
                        assertEquals("committed", repository.getEntry(0).name)
                        assertEquals(0, observable.latestIndexFlow.value)
                        db.execute("DROP TRIGGER reject_settings_update")
                        db.execute("CREATE TRIGGER reject_index_insert BEFORE INSERT ON agent_commits WHEN NEW.type = 'user_message' BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { session.runtime.appendUserMessage(listOf(ContentItem.InputText("uncommitted"))) }
                        assertNull(session.storage.getCommitAtOrBefore(Int.MAX_VALUE, AgentCommitType.IndexTypes))
                        assertEquals(0, session.storage.latestIndex())
                        db.execute("DROP TRIGGER reject_index_insert")
                        val originalCatalog = db.query("SELECT * FROM session_catalog_view")
                        val message = StableUserMessage(listOf(ContentItem.InputText("batch fixture")))
                        val renamed = settings("catalog batch").copy(cwd = Path("/batch/cwd"))
                        val timestamp = Instant.fromEpochSeconds(123)
                        db.execute("""
                            CREATE TRIGGER reject_catalog BEFORE UPDATE ON session_catalog_view
                            BEGIN SELECT RAISE(ABORT, 'injected after history write'); END
                        """)
                        assertFailsWith<SqliteFailure> { session.storage.commit(listOf(renamed, message), timestamp) }
                        assertEquals(0, session.storage.latestIndex())
                        assertEquals(0, observable.latestIndexFlow.value)
                        assertEquals(originalCatalog, db.query("SELECT * FROM session_catalog_view"))
                        db.execute("DROP TRIGGER reject_catalog")
                        assertEquals(1, session.storage.commit(listOf(renamed, message), timestamp))
                        val newCatalog = db.query("SELECT * FROM session_catalog_view").single()
                        assertEquals(listOf("catalog batch", "/batch/cwd"), newCatalog.subList(1, 3))
                        assertEquals(originalCatalog.single()[3], newCatalog[3])
                        assertEquals("1970-01-01T00:02:03.000000000Z", newCatalog[4])
                        db.execute("""
                            CREATE TRIGGER reject_catalog BEFORE UPDATE ON session_catalog_view
                            BEGIN SELECT RAISE(ABORT, 'injected after history delete'); END
                        """)
                        assertFailsWith<SqliteFailure> { session.storage.revert(1) }
                        assertEquals(1, session.storage.latestIndex())
                        assertEquals(newCatalog, db.query("SELECT * FROM session_catalog_view").single())
                        db.execute("DROP TRIGGER reject_catalog")
                        session.storage.revert(1)
                        assertEquals(originalCatalog, db.query("SELECT * FROM session_catalog_view"))
                        db.execute("CREATE TRIGGER reject_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { repository.createFork(0) }
                        assertEquals(listOf(0), repository.entries.value)
                        assertFailsWith<SqliteFailure> { repository.create() }
                        db.execute("DROP TRIGGER reject_session")
                        db.execute("CREATE TRIGGER reject_archive BEFORE UPDATE OF completed ON agents BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { repository.getEntry(0).archive() }
                        assertFalse(repository.getEntry(0).archived)
                        db.execute("DROP TRIGGER reject_archive")
                        db.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { repository.delete(0) }
                        assertEquals(listOf(0), repository.entries.value)
                        db.execute("DROP TRIGGER reject_delete")
                        assertEquals("committed", repository.open(0).storage.getSettingsAt(0).name)
                    }
                }
            }
            assertFalse(fs.exists(Path(root, "sessions")))
        }
    }
    test("fork inside runtime modify and concurrent repository writes avoid nested transactions") {
        fixture { root ->
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(root, dependencies).use { repository ->
                    val sourceIndex = repository.create()
                    val source = repository.open(sourceIndex)
                    source.runtime.modify { it.initialize(settings("source")) }
                    var targetIndex = -1
                    source.runtime.modify {
                        targetIndex = repository.createFork(sourceIndex)
                        repository.open(targetIndex).runtime.modify { target -> target.revert(1) }
                    }
                    assertEquals("source", repository.getEntry(targetIndex).name)
                    val indexes = coroutineScope { (0 until 8).map { async { repository.create() } }.awaitAll() }
                    assertEquals(8, indexes.distinct().size)
                    coroutineScope {
                        indexes.map { index -> async {
                            repository.open(index).runtime.modify { it.initialize(settings("session-$index")) }
                        } }.awaitAll()
                    }
                    assertEquals(10, repository.listEntries().size)
                    repository.delete(targetIndex)
                    repository.closeAndJoin()
                    assertFalse(fs.exists(Path(root, "sessions", targetIndex.toString())))
                }
            }
        }
    }
    test("scope cancellation closes storage owners without creating session mirrors") {
        fixture { root ->
            testKodexAgentDependencies().use { dependencies ->
                val owner = SupervisorJob()
                val scope = CoroutineScope(coroutineContext + owner)
                val repository = scope.SqliteKodexSessionRepository(root, dependencies)
                val index = repository.create()
                val session = repository.open(index)
                session.runtime.modify { it.initialize(settings("closed")) }
                owner.cancelAndJoin()
                assertFalse(session.coroutineContext[Job]!!.isActive)
                assertFalse(fs.exists(Path(root, "sessions")))
                assertFailsWith<IllegalStateException> { session.storage.latestIndex() }
                assertFailsWith<IllegalStateException> { repository.list() }
            }
        }
    }
    test("fails closed when authoritative database is missing or uninitialized for an existing Home") {
        fixture { root ->
            testKodexAgentDependencies().use { dependencies ->
                fs.createDirectories(Path(root, "sessions", "0", "settings"))
                fs.writeString(Path(root, "sessions", "0", "settings", "0.json"), """{"model":"test","threadName":"keep"}""")
                fs.writeString(Path(root, "version.json"), "\"0.6.3\"")
                assertFailsWith<IOException> { SqliteKodexSessionRepository(root, dependencies) }
                assertTrue(coroutineContext[Job]!!.children.none { it.isActive })
                assertFalse(fs.exists(Path(root, "kodex.db")))
                assertEquals("""{"model":"test","threadName":"keep"}""", fs.readString(Path(root, "sessions", "0", "settings", "0.json")))

                // Also reject a non-empty SQLite file whose user_version is still 0.
                openSqliteConnection(Path(root, "kodex.db")).use { rawDb ->
                    rawDb.execute("CREATE TABLE dummy(x INTEGER)")
                }
                assertFailsWith<IOException> { SqliteKodexSessionRepository(root, dependencies) }
                assertTrue(coroutineContext[Job]!!.children.none { it.isActive })
                assertEquals("""{"model":"test","threadName":"keep"}""", fs.readString(Path(root, "sessions", "0", "settings", "0.json")))
            }
        }
    }
    test("idle TTL eviction drains session without self-deadlocking and allows clean reopen and shutdown") {
        withContext(Dispatchers.Default) {
            fixture { root ->
                testKodexAgentDependencies().use { dependencies ->
                    val repository = SqliteKodexSessionRepository(
                        root = root,
                        dependencies = dependencies,
                        sessionTtl = 40.milliseconds,
                    )
                    try {
                        val index = repository.create()
                        val first = repository.open(index)
                        first.runtime.modify { it.initialize(settings("ttl-sqlite")) }
                        val agentId = repository.getRootAgentId(index)
                        assertTrue(agentId in repository.openSessions.value)
                        withTimeout(5000.milliseconds) {
                            while (agentId in repository.openSessions.value) {
                                delay(10.milliseconds)
                            }
                            first.coroutineContext[Job]?.join()
                        }
                        assertFalse(first.coroutineContext[Job]?.isActive ?: true)
                        val reopened = repository.open(index)
                        assertNotSame(first, reopened)
                        assertEquals("ttl-sqlite", reopened.storage.getSettingsAt(0).name)
                    } finally {
                        repository.closeAndJoin()
                    }
                }
            }
        }
    }
}
