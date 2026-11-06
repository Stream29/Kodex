@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.sqlite.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.*
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.coroutines.*
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
private fun settings(name: String) = KodexAgentSettings(model = OpenAiModelId("test"), threadName = name)
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
    test("SQLite catalog, fork, revert, archive and persistence ignore corrupted and deleted mirrors") {
        fixture { root ->
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(root, dependencies).use { repository ->
                    assertTrue(repository.entries.value.isEmpty())
                    val first = repository.create()
                    assertEquals(0, first)
                    assertNull(repository.getEntry(first).threadName)
                    val session = repository.open(first)
                    assertSame(session, repository.open(first))
                    session.runtime.modify { it.initialize(settings("original")) }
                    val created = repository.readCreatedAt(first)
                    assertNotNull(created)
                    val appendedIndex = session.storage.commit(
                        listOf(
                            settings("new"),
                            CommitTimestamp(Instant.fromEpochSeconds(123, 456)),
                        ),
                    )
                    assertEquals(1, appendedIndex)
                    val observable = session.storage as ObservableKodexAgentStorage
                    assertEquals(1, observable.latestIndexFlow.value)
                    val nonce = observable.cacheNonce.value
                    val second = repository.createFork(first)
                    assertEquals(1, second)
                    val fork = repository.open(second)
                    assertEquals(Path(fs.resolve(root), "sessions", "1").toStorageUri(), fork.storage.uri)
                    assertNotEquals(session.storage.uri, fork.storage.uri)
                    assertEquals("new", repository.getEntry(second).threadName)
                    fork.storage.revert(1)
                    assertEquals("original", repository.getEntry(second).threadName)
                    assertEquals(created, repository.readUpdatedAt(second))
                    session.storage.revert(1)
                    assertNotEquals(nonce, observable.cacheNonce.value)
                    assertEquals(0, observable.latestIndexFlow.value)
                    repository.getEntry(first).archive()
                    assertEquals(listOf(second), repository.listEntries(false).map { it.entryIndex })
                    assertEquals(listOf(first, second), repository.list())
                    repository.getEntry(first).unarchive()
                    repository.closeAndJoin()
                    assertEquals("0", fs.readString(Path(root, "sessions", "0", "settings", "latest.json")))
                    assertEquals("-1", fs.readString(Path(root, "sessions", "0", "index", "latest.json")))
                    assertEquals("\"$created\"", fs.readString(Path(root, "sessions", "0", "timestamp", "0.json")))
                    fs.writeString(Path(root, "sessions", "0", "settings", "latest.json"), "corrupted")
                    fs.writeString(Path(root, "sessions", "0", "lock.json"), "corrupted")
                    removeTree(Path(root, "sessions", "1"))
                    SqliteKodexSessionRepository(root, dependencies).use { reopened ->
                        assertEquals(listOf(0, 1), reopened.list())
                        assertEquals("original", reopened.getEntry(0).threadName)
                        assertEquals(created, reopened.readCreatedAt(1))
                        assertEquals("original", reopened.open(1).storage.getSettingsAt(0).threadName)
                        reopened.delete(0)
                        assertEquals(listOf(1), reopened.entries.value)
                        assertEquals(0, reopened.create())
                        assertEquals(-1, reopened.open(0).storage.latestIndex())
                    }
                    assertFalse(fs.exists(Path(root, "sessions", "0", "settings", "0.json")))
                }
            }
        }
    }
    test("rollback leaves cache flows and mirror untouched") {
        fixture { root ->
            fs.createDirectories(root)
            openSqliteConnection(Path(root, "kodex.db")).use { db ->
                ensureSqlitePhaseOneSchema(db)
                db.execute("INSERT INTO sessions VALUES(0,0)")
                db.execute("INSERT INTO agents VALUES(0,0,NULL)")
                testKodexAgentDependencies().use { dependencies ->
                    SqliteKodexSessionRepository(root, dependencies).use { repository ->
                        val session = repository.open(0)
                        val observable = session.storage as ObservableKodexAgentStorage
                        val nonce = observable.cacheNonce.value
                        db.execute("""CREATE TRIGGER reject_init BEFORE INSERT ON agent_commits WHEN NEW.type = 'timestamp' AND NEW."index" = 0 BEGIN SELECT RAISE(ABORT, 'injected'); END""")
                        assertFailsWith<SqliteFailure> { session.runtime.modify { it.initialize(settings("failed")) } }
                        assertEquals(-1, observable.latestIndexFlow.value)
                        assertEquals(nonce, observable.cacheNonce.value)
                        assertEquals(-1, session.storage.latestIndex())
                        assertNull(repository.getEntry(0).threadName)
                        db.execute("DROP TRIGGER reject_init")
                        session.runtime.modify { it.initialize(settings("committed")) }
                        assertEquals(0, observable.latestIndexFlow.value)
                        db.execute("""CREATE TRIGGER reject_settings_update BEFORE INSERT ON agent_commits WHEN NEW.type = 'settings' AND NEW."index" > 0 BEGIN SELECT RAISE(ABORT, 'injected'); END""")
                        assertFailsWith<SqliteFailure> { session.runtime.updateSettings(settings("uncommitted")) }
                        assertEquals("committed", repository.getEntry(0).threadName)
                        assertEquals(0, observable.latestIndexFlow.value)
                        db.execute("DROP TRIGGER reject_settings_update")
                        db.execute("CREATE TRIGGER reject_index_insert BEFORE INSERT ON agent_commits WHEN NEW.type = 'user_message' BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { session.runtime.appendUserMessage(listOf(ContentItem.InputText("uncommitted"))) }
                        assertNull(session.storage.floorToIndex(Int.MAX_VALUE, AgentCommitType.IndexTypes))
                        assertEquals(0, session.storage.latestIndex())
                        db.execute("DROP TRIGGER reject_index_insert")
                        db.execute("CREATE TRIGGER reject_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { repository.createFork(0) }
                        assertEquals(listOf(0), repository.entries.value)
                        assertFailsWith<SqliteFailure> { repository.create() }
                        db.execute("DROP TRIGGER reject_session")
                        db.execute("CREATE TRIGGER reject_archive BEFORE UPDATE ON sessions BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { repository.getEntry(0).archive() }
                        assertFalse(repository.getEntry(0).archived)
                        db.execute("DROP TRIGGER reject_archive")
                        db.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT, 'injected'); END")
                        assertFailsWith<SqliteFailure> { repository.delete(0) }
                        assertEquals(listOf(0), repository.entries.value)
                        db.execute("DROP TRIGGER reject_delete")
                        assertEquals("committed", repository.open(0).storage.getSettingsAt(0).threadName)
                    }
                }
            }
            assertEquals("0", fs.readString(Path(root, "sessions", "0", "settings", "latest.json")))
            assertFalse(fs.exists(Path(root, "sessions", "1")))
            assertFalse(fs.exists(Path(root, "sessions", "0", "archive.mark")))
        }
    }
    test("mirror failure is reported and later FIFO work still materializes") {
        fixture { root ->
            val failures = mutableListOf<Throwable>()
            testKodexAgentDependencies().use { dependencies ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var failOnce = true
                val failing = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (failOnce && destination.name == "latest.json") {
                            failOnce = false
                            entered.complete(Unit)
                            release.await()
                            throw IllegalStateException("injected mirror failure")
                        }
                        fs.atomicMove(source, destination)
                    }
                }
                try {
                    SqliteKodexSessionRepository(root, dependencies, failing, reportMirrorFailure = { failures += it }).use { repository ->
                        val index = repository.create()
                        entered.await()
                        // Disk is blocked, but committed state and subsequent writes remain available.
                        val session = repository.open(index)
                        session.runtime.modify { it.initialize(settings("survived")) }
                        repository.getEntry(index).archive()
                        repository.getEntry(index).unarchive()
                        repository.getEntry(index).archive()
                        assertEquals("survived", repository.getEntry(index).threadName)
                        assertTrue(repository.getEntry(index).archived)
                        release.complete(Unit)
                    }
                    assertEquals(1, failures.size)
                    assertTrue(fs.exists(Path(root, "sessions", "0", "archive.mark")))
                    assertTrue(fs.exists(Path(root, "sessions", "0", "settings", "0.json")))
                } finally { release.complete(Unit) }
            }
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
                    assertEquals("source", repository.getEntry(targetIndex).threadName)
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
    test("scope cancellation drains committed mirrors and closes storage owners") {
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
                assertTrue(fs.exists(Path(root, "sessions", "0", "settings", "0.json")))
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
    test("cancelled mirror owner rejects writes even before worker starts") {
        runTest {
            val failures = mutableListOf<Throwable>()
            val owner = CoroutineScope(coroutineContext + SupervisorJob())
            owner.cancel()
            val mirror = SequentialMirrorQueue(owner, failures::add)
            runCurrent()
            assertFalse(mirror.enqueue { fail("cancelled worker accepted a write") })
            assertEquals(1, failures.size)
            assertIs<IOException>(failures.single())
            mirror.closeAndDrain()
        }
    }
    test("bounded mirror queue is sequential, asynchronous, best effort and times out hung drain") {
        runTest {
            val events = mutableListOf<String>()
            val failures = mutableListOf<Throwable>()
            val gate = CompletableDeferred<Unit>()
            val mirror = SequentialMirrorQueue(this, failures::add)
            try {
                assertTrue(mirror.enqueue { events.add("start1"); gate.await(); events.add("end1") })
                runCurrent()
                assertEquals(listOf("start1"), events)
                val synthetic = IllegalStateException("synthetic mirror failure")
                assertTrue(mirror.enqueue { events.add("failed2"); throw synthetic })
                assertTrue(mirror.enqueue { events.add("write3") })
                gate.complete(Unit)
                mirror.closeAndDrain()
                assertEquals(listOf("start1", "end1", "failed2", "write3"), events)
                assertEquals(listOf<Throwable>(synthetic), failures)
                assertFalse(mirror.enqueue { fail("closed queue executed write") })
            } finally { gate.complete(Unit); mirror.closeAndDrain() }

            val timeoutFailures = mutableListOf<Throwable>()
            val started = CompletableDeferred<Unit>()
            val unblock = CompletableDeferred<Unit>()
            val bounded = SequentialMirrorQueue(
                scope = this,
                reportFailure = timeoutFailures::add,
                capacity = 1,
                drainTimeout = 50.milliseconds,
            )
            try {
                assertTrue(bounded.enqueue {
                    started.complete(Unit)
                    withContext(NonCancellable) { unblock.await() }
                })
                runCurrent()
                assertTrue(started.isCompleted)
                assertTrue(bounded.enqueue {})
                assertFalse(bounded.enqueue {})
                bounded.closeAndDrain()
                assertEquals(2, timeoutFailures.size)
                assertTrue(timeoutFailures.all { it is IOException })
            } finally { unblock.complete(Unit) }
        }
    }
}
