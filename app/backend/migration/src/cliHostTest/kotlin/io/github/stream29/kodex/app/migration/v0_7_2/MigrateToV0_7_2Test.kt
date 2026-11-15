package io.github.stream29.kodex.app.migration.v0_7_2

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.ext.activeMessageWindowAt
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_7_0.migrateToV0_7_0
import io.github.stream29.kodex.app.migration.v0_7_1.migrateToV0_7_1
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

public val migrateToV0_7_2Test by testSuite {
    testFixture { temporaryHome() } closeWith { removeTree(this) } asParameterForEach {
        test("absent Home publishes an initialized 0.7.2 database with steer support") { home ->
            removeTree(home)
            migrateToV0_7_2(home, fs)
            assertRollbackJournal(Path(home, "kodex.db"))
            database(home) { db ->
                assertEquals(listOf(listOf(1L)), db.query("PRAGMA user_version"))
                val sql = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'agent_commits'")
                    .single().single() as String
                assertTrue("'steer'" in sql)
                assertTrue("'ask_user'" in sql)
                assertTrue("'event_ask_user'" in sql)
                assertTrue("'event_schedule'" in sql)
                assertTrue("'event_terminal_exited'" in sql)
                assertTrue("'await_event_tool_event'" in sql)
                for (table in listOf("sessions", "agents", "agent_commits", "schedules", "session_catalog")) {
                    assertEquals(listOf(listOf(0L)), db.query("SELECT count(*) FROM $table"))
                }
            }
            assertNoStaging(home)
        }

        test("upgrades 0.7.1 database by backfilling compaction retained_items and enabling steer") { home ->
            emptySession(home, 1)
            record(home, 1, "settings", 0, Settings)
            record(home, 1, "timestamp", 0, Created)
            record(home, 1, "index", 1, UserOne)
            record(home, 1, "index", 2, AssistantOne)
            record(home, 1, "work", 3, LegacyCompactionOne)
            record(home, 1, "index", 4, UserTwo)
            record(home, 1, "work", 5, LegacyCompactionTwo)
            record(home, 1, "timestamp", 5, Updated)
            migrateToV0_7_0(home, fs)
            migrateToV0_7_1(home, fs)
            record(home, 1, "index", 3, LegacyCompactionOne)
            record(home, 1, "index", 5, LegacyCompactionTwo)
            fs.writeString(Path(home, "version.json"), "\"0.7.1\"")

            upgrade(home)
            assertEquals("\"0.7.2\"", fs.readString(Path(home, "version.json")))

            val expectedUserOne = StableUserMessage(listOf(ContentItem.InputText("first user")))
            val expectedUserTwo = StableUserMessage(listOf(ContentItem.InputText("second user")))

            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    val storage = repository.open(1).storage
                    val compactionOne = assertIs<StableContextCompaction>(
                        storage.getCommitExact(3, AgentCommitType.IndexTypes)?.commit,
                    )
                    assertEquals("enc-1", compactionOne.encryptedContent)
                    assertEquals(listOf(expectedUserOne), compactionOne.retainedItems)

                    val compactionTwo = assertIs<StableContextCompaction>(
                        storage.getCommitExact(5, AgentCommitType.IndexTypes)?.commit,
                    )
                    assertEquals("enc-2", compactionTwo.encryptedContent)
                    assertEquals(listOf(expectedUserOne, expectedUserTwo), compactionTwo.retainedItems)

                    val window = storage.activeMessageWindowAt(5)
                    assertEquals(listOf(compactionTwo), window)

                    val mirrorFive = OpenAiJsonCodec.decodeFromString(
                        CleanIndexEntry.serializer(),
                        fs.readString(Path(home, "sessions", "1", "index", "5.json")),
                    )
                    assertEquals(compactionTwo, mirrorFive)

                    val queued = listOf(StableUserMessage(listOf(ContentItem.InputText("queued steer"))))
                    val steerIndex = storage.commit(listOf(SteerSnapshot(queued)), Instant.parse("2026-10-25T12:00:09Z"))
                    assertEquals(6, steerIndex)
                    assertEquals(queued, storage.getSteerAt(6))
                }
            }
            assertNoStaging(home)
        }

        for (afterMove in listOf(false, true)) {
            test("publish interruption ${if (afterMove) "after" else "before"} atomic move recovers on reentry") { home ->
                emptySession(home, 0)
                record(home, 0, "settings", 0, Settings)
                record(home, 0, "timestamp", 0, Created)
                record(home, 0, "index", 1, UserOne)
                record(home, 0, "work", 2, LegacyCompactionOne)
                migrateToV0_7_0(home, fs)
                migrateToV0_7_1(home, fs)
                record(home, 0, "index", 2, LegacyCompactionOne)
                fs.writeString(Path(home, "version.json"), "\"0.7.1\"")
                val failing = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (destination == Path(home, "kodex.db")) {
                            assertRollbackJournal(source)
                            assertFalse(fs.exists(Path(home, "$Staging-wal")))
                            assertFalse(fs.exists(Path(home, "$Staging-shm")))
                            if (afterMove) fs.atomicMove(source, destination)
                            throw IOException("injected 0.7.2 publish failure")
                        }
                        fs.atomicMove(source, destination)
                    }
                }
                assertFailsWith<IOException> { upgrade(home, failing) }
                assertTrue(fs.exists(Path(home, "kodex.db")))
                assertEquals("\"0.7.1\"", fs.readString(Path(home, "version.json")))
                assertNoStaging(home)
                upgrade(home, if (afterMove) noRepublish(home) else fs)
                assertEquals("\"0.7.2\"", fs.readString(Path(home, "version.json")))
                val mirrorTwo = OpenAiJsonCodec.decodeFromString(
                    CleanIndexEntry.serializer(),
                    fs.readString(Path(home, "sessions", "0", "index", "2.json")),
                ) as StableContextCompaction
                assertEquals(
                    listOf(StableUserMessage(listOf(ContentItem.InputText("first user")))),
                    mirrorTwo.retainedItems,
                )
            }
        }
    }
}

private val fs = SystemCoroutineFileSystem
private const val Staging = ".kodex-migration-0.7.2-staging.db"
private val Timelines = listOf("index", "work", "settings", "timestamp", "token-count", "unstable")
private const val Settings = """{"model":"synthetic","threadName":"migration","cwd":"/synthetic"}"""
private const val UserOne = """{"type":"user_message","content":[{"type":"input_text","text":"first user"}]}"""
private const val AssistantOne = """{"type":"assistant_message","content":[{"type":"output_text","text":"skipped assistant"}]}"""
private const val LegacyCompactionOne = """{"type":"context_compaction","encrypted_content":"enc-1"}"""
private const val UserTwo = """{"type":"user_message","content":[{"type":"input_text","text":"second user"}]}"""
private const val LegacyCompactionTwo = """{"type":"context_compaction","encrypted_content":"enc-2"}"""
private const val Created = "\"2026-10-25T12:00:00.123456789Z\""
private const val Updated = "\"2026-10-25T12:00:07.987654321Z\""

private suspend fun temporaryHome(): Path =
    Path(SystemTemporaryDirectory, "kodex-migration-072-${Random.nextLong()}").also { fs.createDirectories(it) }

private suspend fun removeTree(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { removeTree(it) }
    fs.delete(path, mustExist = false)
}

private suspend fun emptySession(home: Path, index: Int) {
    for (timeline in Timelines) {
        val directory = Path(home, "sessions", index.toString(), timeline)
        fs.createDirectories(directory)
        fs.writeString(Path(directory, "latest.json"), "-1")
    }
}

private suspend fun record(home: Path, session: Int, timeline: String, index: Int, payload: String) {
    val directory = Path(home, "sessions", session.toString(), timeline)
    fs.writeString(Path(directory, "$index.json"), payload)
    fs.writeString(Path(directory, "latest.json"), index.toString())
}

private suspend fun assertNoStaging(home: Path) {
    for (suffix in listOf("", "-wal", "-shm")) assertFalse(fs.exists(Path(home, Staging + suffix)))
}

private suspend fun assertRollbackJournal(path: Path) {
    val header = fs.readBytes(path)
    assertEquals(1.toByte(), header[18])
    assertEquals(1.toByte(), header[19])
}

private inline fun database(home: Path, block: (SqliteConnection) -> Unit) =
    openSqliteConnection(Path(home, "kodex.db")).use(block)

private fun noRepublish(home: Path): CoroutineFileSystem = object : CoroutineFileSystem by fs {
    override suspend fun atomicMove(source: Path, destination: Path) {
        check(destination != Path(home, "kodex.db")) { "Matching 0.7.2 database must be reused" }
        fs.atomicMove(source, destination)
    }
}

private suspend fun CoroutineScope.upgrade(home: Path, fileSystem: CoroutineFileSystem = fs) {
    prepareKodexHome(home, MigrationVersion("0.7.2"), KodexHomeMigrations, fileSystem).closeAndJoin()
}
