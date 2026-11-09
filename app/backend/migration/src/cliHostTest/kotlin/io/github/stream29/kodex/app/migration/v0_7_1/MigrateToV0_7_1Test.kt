package io.github.stream29.kodex.app.migration.v0_7_1

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_7_0.migrateToV0_7_0
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
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

public val migrateToV0_7_1Test by testSuite {
    testFixture { temporaryHome() } closeWith { removeTree(this) } asParameterForEach {
        test("absent Home publishes an initialized 0.7.1 database with created_at and idx_sessions_archived") { home ->
            removeTree(home)
            migrateToV0_7_1(home, fs)
            assertRollbackJournal(Path(home, "kodex.db"))
            database(home) { db ->
                assertEquals(listOf(listOf(1L)), db.query("PRAGMA user_version"))
                val columns = db.query("PRAGMA table_info(agent_commits)").mapNotNull { it.getOrNull(1) as? String }
                assertTrue("created_at" in columns)
                assertEquals(
                    1,
                    db.query("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'idx_sessions_archived'").size,
                )
                for (table in listOf("sessions", "agents", "agent_commits", "session_catalog")) {
                    assertEquals(listOf(listOf(0L)), db.query("SELECT count(*) FROM $table"))
                }
            }
            assertNoStaging(home)
        }

        for (version in listOf(null, "0.3.2", "0.5.2")) {
            test("${version ?: "unversioned"} Home upgrades through every registered migration to 0.7.1") { home ->
                emptySession(home, 0)
                record(home, 0, "settings", 0, Settings)
                record(home, 0, "timestamp", 0, Created)
                record(home, 0, "token-count", 0, if (version == "0.5.2") TokenCount else "42")
                if (version != null) fs.writeString(Path(home, "version.json"), "\"$version\"")
                val activated = mutableListOf<MigrationVersion>()
                prepareKodexHome(home, MigrationVersion("0.7.1"), KodexHomeMigrations, fs) { _, target ->
                    activated += target
                }.useHandle { handle -> assertEquals(MigrationVersion("0.7.1"), handle.version) }
                val expectedMigrations = KodexHomeMigrations
                    .filter { (version?.let(::MigrationVersion) ?: MigrationVersion("0.3.2")) < it.toVersion && it.toVersion <= MigrationVersion("0.7.1") }
                    .map { it.toVersion }
                assertEquals(expectedMigrations, activated)
                assertEquals("\"0.7.1\"", fs.readString(Path(home, "version.json")))
                database(home) { db ->
                    assertEquals(
                        listOf(listOf(0L)),
                        db.query("SELECT count(*) FROM agent_commits WHERE type IN ('timestamp', 'compaction_point')"),
                    )
                    assertEquals(
                        listOf(listOf(0L, 0L, "migration", "/synthetic", "2026-10-25T12:00:00.123456789Z", "2026-10-25T12:00:00.123456789Z")),
                        db.query("SELECT * FROM session_catalog"),
                    )
                }
                assertNoStaging(home)
            }
        }

        test("upgrades 0.7.0 database by backfilling created_at removing timestamp and compaction_point and enabling llm_request_meta") { home ->
            populatedSession(home, 2)
            record(home, 2, "index", 4, """{"type":"compaction_point","replacement_history":[]}""")
            record(home, 2, "timestamp", 4, "\"2026-10-25T12:00:04Z\"")
            record(home, 2, "index", 7, Index)
            record(home, 2, "timestamp", 7, Updated)
            emptySession(home, 15)
            migrateToV0_7_0(home, fs)
            fs.writeString(Path(home, "version.json"), "\"0.7.0\"")
            database(home) { db ->
                assertTrue(db.query("SELECT count(*) FROM agent_commits WHERE type = 'timestamp'").single().single() as Long > 0L)
                assertEquals(listOf(listOf(1L)), db.query("SELECT count(*) FROM agent_commits WHERE type = 'compaction_point'"))
            }

            upgrade(home)
            assertEquals("\"0.7.1\"", fs.readString(Path(home, "version.json")))

            database(home) { db ->
                assertEquals(
                    listOf(listOf(0L)),
                    db.query("SELECT count(*) FROM agent_commits WHERE type IN ('timestamp', 'compaction_point')"),
                )
                assertEquals(
                    listOf(listOf("2026-10-25T12:00:00.123456789Z"), listOf("2026-10-25T12:00:07.987654321Z")),
                    db.query("""SELECT DISTINCT created_at FROM agent_commits WHERE agent_id = 2 ORDER BY "index""""),
                )
                assertEquals(
                    listOf(listOf(2L, 0L, "migration", "/synthetic", "2026-10-25T12:00:00.123456789Z", "2026-10-25T12:00:07.987654321Z")),
                    db.query("SELECT * FROM session_catalog WHERE session_id = 2"),
                )
            }

            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    val storage = repository.open(2).storage
                    assertEquals("migration", checkNotNull(storage.getSettingsAt(7)).threadName)
                    assertEquals(Instant.parse("2026-10-25T12:00:07.987654321Z"), storage.getCommitExact(7)?.createdAt)
                    assertEquals(
                        OpenAiJsonCodec.decodeFromString(CleanIndexEntry.serializer(), Index),
                        storage.getCommitAtOrBefore(7, AgentCommitType.IndexTypes)?.commit,
                    )
                    assertEquals(
                        OpenAiJsonCodec.decodeFromString(StableWorkEvent.serializer(), Work),
                        storage.getCommitAtOrBefore(7, AgentCommitType.WorkTypes)?.commit,
                    )
                    assertEquals(
                        OpenAiJsonCodec.decodeFromString(LlmRequestMeta.Response.serializer(), TokenCount),
                        storage.getLlmRequestMetaAtOrNull(7),
                    )
                    assertEquals(
                        OpenAiJsonCodec.decodeFromString(ListSerializer(UnstableCleanEvent.serializer()), Unstable),
                        storage.getUnstableAtOrNull(7),
                    )
                    val newMeta = LlmRequestMeta.Response(totalTokens = 99L, latencyMs = 123L)
                    val committedIndex = storage.commit(listOf(newMeta), Instant.parse("2026-10-25T12:00:08Z"))
                    assertEquals(8, committedIndex)
                    assertEquals(newMeta, storage.getLlmRequestMetaAtOrNull(8))
                    assertEquals(Instant.parse("2026-10-25T12:00:00.123456789Z"), repository.getEntry(2).createdAt)
                    assertEquals(Instant.parse("2026-10-25T12:00:08Z"), repository.getEntry(2).lastActivityAt)
                    assertNull(repository.getEntry(15).createdAt)
                }
            }
            assertNoStaging(home)
        }

        for (afterMove in listOf(false, true)) {
            test("publish interruption ${if (afterMove) "after" else "before"} atomic move recovers on reentry") { home ->
                populatedSession(home, 0)
                migrateToV0_7_0(home, fs)
                fs.writeString(Path(home, "version.json"), "\"0.7.0\"")
                val failing = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (destination == Path(home, "kodex.db")) {
                            assertRollbackJournal(source)
                            assertFalse(fs.exists(Path(home, "$Staging-wal")))
                            assertFalse(fs.exists(Path(home, "$Staging-shm")))
                            if (afterMove) fs.atomicMove(source, destination)
                            throw IOException("injected 0.7.1 publish failure")
                        }
                        fs.atomicMove(source, destination)
                    }
                }
                assertFailsWith<IOException> { upgrade(home, failing) }
                assertTrue(fs.exists(Path(home, "kodex.db")))
                assertEquals("\"0.7.0\"", fs.readString(Path(home, "version.json")))
                assertNoStaging(home)
                upgrade(home, if (afterMove) noRepublish(home) else fs)
                assertEquals("\"0.7.1\"", fs.readString(Path(home, "version.json")))
            }
        }

        test("stale staging database and sidecars are removed before rebuilding") { home ->
            populatedSession(home, 0)
            migrateToV0_7_0(home, fs)
            for (suffix in listOf("", "-wal", "-shm")) {
                fs.writeString(Path(home, Staging + suffix), "stale and invalid")
            }
            migrateToV0_7_1(home, fs)
            assertNoStaging(home)
            database(home) { db ->
                assertEquals(listOf(listOf(10L)), db.query("SELECT count(*) FROM agent_commits"))
            }
        }
    }
}

private val fs = SystemCoroutineFileSystem
private const val Staging = ".kodex-migration-0.7.1-staging.db"
private val Names = linkedMapOf(
    "index" to "user_message",
    "work" to "text_tool_event",
    "settings" to "settings",
    "timestamp" to "timestamp",
    "token-count" to "token_count_response",
    "unstable" to "unstable",
)
private const val Settings = """{"model":"synthetic","threadName":"migration","cwd":"/synthetic","future":{"nested":[1,null,"opaque"]}}"""
private const val Index = """{"type":"user_message","content":[{"type":"input_text","text":"hello","future_nested":9}],"future_top":8}"""
private const val Work = """{"type":"text_tool_event","call_id":"c","name":"test","arguments":{"opaque":"{unparsed}","z":99},"result":"OK","success":true,"future":42}"""
private const val TokenCount = """{"kind":"response","total_tokens":42,"future":{"retained":true}}"""
private const val Unstable = """[{"type":"custom","call_id":"pending","name":"test","input":"opaque","future":{"retained":true}}]"""
private const val Created = "\"2026-10-25T12:00:00.123456789Z\""
private const val Updated = "\"2026-10-25T12:00:07.987654321Z\""

private suspend fun temporaryHome(): Path =
    Path(SystemTemporaryDirectory, "kodex-migration-071-${Random.nextLong()}").also { fs.createDirectories(it) }

private suspend fun removeTree(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { removeTree(it) }
    fs.delete(path, mustExist = false)
}

private suspend fun emptySession(home: Path, index: Int) {
    for (timeline in Names.keys) {
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

private suspend fun populatedSession(home: Path, index: Int) {
    emptySession(home, index)
    for ((timeline, payload) in listOf(
        "index" to Index,
        "work" to Work,
        "settings" to Settings,
        "token-count" to TokenCount,
        "unstable" to Unstable,
    )) {
        record(home, index, timeline, 0, payload)
        record(home, index, timeline, 7, payload)
    }
    record(home, index, "timestamp", 0, Created)
    record(home, index, "timestamp", 7, Updated)
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
        check(destination != Path(home, "kodex.db")) { "Matching 0.7.1 database must be reused" }
        fs.atomicMove(source, destination)
    }
}

private suspend fun CoroutineScope.upgrade(home: Path, fileSystem: CoroutineFileSystem = fs) {
    prepareKodexHome(home, MigrationVersion("0.7.1"), KodexHomeMigrations, fileSystem).closeAndJoin()
}

private suspend fun <T> io.github.stream29.kodex.app.migration.KodexHomeHandle.useHandle(
    block: suspend (io.github.stream29.kodex.app.migration.KodexHomeHandle) -> T,
): T = CoroutineCloseable(::closeAndJoin).use { block(this) }
