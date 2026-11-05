package io.github.stream29.kodex.app.migration.v0_7_0

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.*
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant

public val migrateToV0_7_0Test by testSuite {
    testFixture { temporaryHome() } closeWith { removeTree(this) } asParameterForEach {
        test("absent Home publishes an initialized empty database without creating sessions") { home ->
            removeTree(home)
            migrateToV0_7_0(home, fs)
            assertRollbackJournal(Path(home, "kodex.db"))
            database(home) { db ->
                assertEquals(listOf(listOf(1L)), db.query("PRAGMA user_version"))
                for (table in listOf("sessions", "agents", "agent_commits", "session_catalog")) {
                    assertEquals(listOf(listOf(0L)), db.query("SELECT count(*) FROM $table"))
                }
            }
            assertFalse(fs.exists(Path(home, "sessions")))
            assertFalse(fs.exists(Path(home, "version.json")))
            assertNoStaging(home)
        }

        for (version in listOf(null, "0.3.2")) {
            test("${version ?: "unversioned"} Home upgrades through every registered migration to 0.7.0") { home ->
                emptySession(home, 0)
                record(home, 0, "settings", 0, Settings)
                record(home, 0, "timestamp", 0, Created)
                // The 0.4.5 entry must convert this old numeric token count before SQLite import.
                record(home, 0, "token-count", 0, "42")
                if (version != null) fs.writeString(Path(home, "version.json"), "\"$version\"")
                val activated = mutableListOf<MigrationVersion>()
                prepareKodexHome(home, MigrationVersion("0.7.0"), KodexHomeMigrations, fs) { _, target ->
                    activated += target
                }.useHandle { handle -> assertEquals(MigrationVersion("0.7.0"), handle.version) }
                assertEquals(KodexHomeMigrations.map { it.toVersion }, activated)
                assertEquals("\"0.7.0\"", fs.readString(Path(home, "version.json")))
                database(home) { db ->
                    val token = db.query("SELECT payload_json FROM agent_commits WHERE type='token_count_legacy'").single().single() as String
                    assertEquals(42L, OpenAiJsonCodec.decodeFromString(TokenCountSnapshot.serializer(), token).totalTokens)
                    assertEquals(listOf(listOf(0L, 0L, "migration", "/synthetic")),
                        db.query("SELECT session_id, archived, thread_name, cwd FROM session_catalog"))
                }
                assertTrue(fs.exists(Path(home, "skills", "kodex-home", "SKILL.md")))
                assertNoStaging(home)
            }
        }

        test("active archived sparse and empty sessions preserve all payloads files and provider identities") { home ->
            populatedSession(home, 2)
            populatedSession(home, 9)
            emptySession(home, 15)
            fs.writeString(Path(home, "sessions", "9", "archive.mark"), "")
            fs.createDirectories(Path(home, "sessions", ".hidden"))
            fs.writeString(Path(home, "sessions", ".hidden", "owned"), "hidden")
            for (relative in listOf("settings.yml", "auth.yml", "unknown.txt", "skills/custom/SKILL.md", "subagents/old", "sessions/2/subagents/legacy")) {
                val path = Path(home, relative)
                fs.createDirectories(checkNotNull(path.parent))
                fs.writeString(path, "preserve $relative")
            }
            fs.writeString(Path(home, "sessions", "2", "work", ".uncommitted.tmp"), "broken temporary")
            val before = snapshot(home)
            migrateToV0_7_0(home, fs)
            assertEquals(before, snapshot(home).filterKeys { it != "kodex.db" })
            database(home) { db ->
                assertEquals(listOf(listOf(2L, 0L), listOf(9L, 1L), listOf(15L, 0L)),
                    db.query("SELECT * FROM sessions ORDER BY session_id"))
                for ((fileName, commitType) in Names) {
                    assertEquals(listOf(listOf(0L), listOf(7L)), db.query(
                        """SELECT "index" FROM agent_commits WHERE agent_id=2 AND type=? ORDER BY "index"""", listOf(commitType)))
                    for (index in listOf(0, 7)) {
                        val raw = fs.readString(Path(home, "sessions", "2", fileName, "$index.json"))
                        val imported = db.query("""SELECT payload_json FROM agent_commits WHERE agent_id=2 AND type=? AND "index"=?""",
                            listOf(commitType, index.toLong())).single().single() as String
                        assertEquals(raw, imported)
                    }
                }
                assertEquals(listOf(listOf(15L, 0L, null, null, null, null)),
                    db.query("SELECT * FROM session_catalog WHERE session_id=15"))
                assertEquals(listOf(listOf(2L, 2L, null)),
                    db.query("SELECT agent_id, session_id, parent_agent_id FROM agents WHERE agent_id=2"))
            }
            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    assertEquals(listOf(2, 9, 15), repository.entries.value)
                    assertEquals(listOf(2, 15), repository.listEntries(false).map { it.entryIndex })
                    assertEquals(listOf(2, 9, 15), repository.listEntries(true).map { it.entryIndex })
                    val storage = repository.open(2).storage
                    assertEquals(FileSystemAgentStorage(Path(home, "sessions", "2"), fs).uri, storage.uri)
                    assertEquals("migration", storage.settings[7].threadName)
                    assertEquals(Instant.parse("2026-10-25T12:00:07.987654321Z"), storage.timestamp[7])
                    assertEquals(OpenAiJsonCodec.decodeFromString(CleanIndexEntry.serializer(), Index), storage.index[7])
                    assertEquals(OpenAiJsonCodec.decodeFromString(StableWorkEvent.serializer(), Work), storage.work[7])
                    assertEquals(OpenAiJsonCodec.decodeFromString(TokenCountSnapshot.serializer(), TokenCount), storage.tokenCount[7])
                    assertEquals(OpenAiJsonCodec.decodeFromString(ListSerializer(UnstableCleanEvent.serializer()), Unstable), storage.unstable[7])
                    assertNull(storage.index.getExact(6))
                    assertEquals(0, storage.index.floorToIndex(6))
                    assertEquals(7, storage.index.ceilToIndex(6))
                    assertEquals(Instant.parse("2026-10-25T12:00:00.123456789Z"), repository.readCreatedAt(2))
                    assertEquals(storage.timestamp[7], repository.readUpdatedAt(2))
                    assertNull(repository.readCreatedAt(15))
                    assertEquals(-1, repository.open(15).storage.settings.latestIndex())
                }
            }
            assertEquals(before, snapshot(home).filterKeys { it != "kodex.db" })
            assertNoStaging(home)
        }

        test("catalog uses exact timestamp zero and latest sparse settings rather than inferred creation") { home ->
            emptySession(home, 0)
            record(home, 0, "settings", 3, """{"model":"synthetic"}""")
            record(home, 0, "timestamp", 7, Updated)
            migrateToV0_7_0(home, fs)
            database(home) { db ->
                assertEquals(listOf(listOf(0L, 0L, "", ".", null, "2026-10-25T12:00:07.987654321Z")),
                    db.query("SELECT * FROM session_catalog"))
            }
        }

        test("maps assistant message phases and token count kinds into fine-grained commit types") { home ->
            emptySession(home, 0)
            record(home, 0, "index", 1, """{"type":"assistant_message","content":[{"type":"output_text","text":"plain"}]}""")
            record(home, 0, "index", 2, """{"type":"assistant_message","phase":"commentary","content":[{"type":"output_text","text":"thinking"}]}""")
            record(home, 0, "index", 3, """{"type":"assistant_message","phase":"final_answer","content":[{"type":"output_text","text":"done"}]}""")
            record(home, 0, "token-count", 0, """{"kind":"initialization","total_tokens":0}""")
            record(home, 0, "token-count", 1, """{"kind":"legacy","total_tokens":10}""")
            record(home, 0, "token-count", 2, """{"kind":"compaction","total_tokens":20}""")
            record(home, 0, "token-count", 3, """{"kind":"response","total_tokens":30}""")
            migrateToV0_7_0(home, fs)
            database(home) { db ->
                assertEquals(
                    listOf(
                        listOf(0L, "token_count_initialization"),
                        listOf(1L, "assistant_message"),
                        listOf(1L, "token_count_legacy"),
                        listOf(2L, "assistant_message_commentary"),
                        listOf(2L, "token_count_compaction"),
                        listOf(3L, "assistant_message_final"),
                        listOf(3L, "token_count_response"),
                    ),
                    db.query("""SELECT "index", type FROM agent_commits WHERE agent_id=0 ORDER BY "index", type"""),
                )
            }
        }

        test("stale staging database and both sidecars are removed before rebuilding") { home ->
            populatedSession(home, 0)
            for (suffix in listOf("", "-wal", "-shm")) fs.writeString(Path(home, Staging + suffix), "stale and invalid")
            migrateToV0_7_0(home, fs)
            assertNoStaging(home)
            database(home) { assertEquals(listOf(listOf(12L)), it.query("SELECT count(*) FROM agent_commits")) }
        }

        for (cancel in listOf(false, true)) {
            test("${if (cancel) "cancellation" else "failure"} during staging import cleans up and supports reentry") { home ->
                populatedSession(home, 0)
                fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
                val before = snapshot(Path(home, "sessions"))
                var injected = false
                val failing = object : CoroutineFileSystem by fs {
                    override suspend fun readBytes(path: Path, maxByteCount: Long): ByteArray {
                        if (!injected && path.name == "7.json" && fs.exists(Path(home, Staging))) {
                            injected = true
                            // At least one record was already inserted in the staging transaction.
                            if (cancel) {
                                currentCoroutineContext().cancel(CancellationException("injected import cancellation"))
                                currentCoroutineContext().ensureActive()
                            }
                            throw IOException("injected import failure")
                        }
                        return fs.readBytes(path, maxByteCount)
                    }
                }
                if (cancel) {
                    supervisorScope {
                        val work = async { upgrade(home, failing) }
                        assertFailsWith<CancellationException> { work.await() }
                    }
                } else assertFailsWith<IOException> { upgrade(home, failing) }
                assertTrue(injected)
                assertEquals(before, snapshot(Path(home, "sessions")))
                assertEquals("\"0.5.2\"", fs.readString(Path(home, "version.json")))
                assertFalse(fs.exists(Path(home, "kodex.db")))
                assertNoStaging(home)
                upgrade(home)
                assertEquals("\"0.7.0\"", fs.readString(Path(home, "version.json")))
            }
        }

        for (afterMove in listOf(false, true)) {
            test("publish interruption ${if (afterMove) "after" else "before"} atomic move recovers") { home ->
                populatedSession(home, 0)
                fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
                val before = snapshot(Path(home, "sessions"))
                val failing = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (destination == Path(home, "kodex.db")) {
                            assertRollbackJournal(source)
                            assertFalse(fs.exists(Path(home, "$Staging-wal")))
                            assertFalse(fs.exists(Path(home, "$Staging-shm")))
                            if (afterMove) fs.atomicMove(source, destination)
                            throw IOException("injected publish failure")
                        }
                        fs.atomicMove(source, destination)
                    }
                }
                assertFailsWith<IOException> { upgrade(home, failing) }
                assertEquals(afterMove, fs.exists(Path(home, "kodex.db")))
                assertEquals("\"0.5.2\"", fs.readString(Path(home, "version.json")))
                assertEquals(before, snapshot(Path(home, "sessions")))
                assertNoStaging(home)
                upgrade(home, if (afterMove) noRepublish(home) else fs)
                assertEquals("\"0.7.0\"", fs.readString(Path(home, "version.json")))
            }
        }

        test("published database is reused after version write fails and 0.7.0 later ignores the mirror") { home ->
            populatedSession(home, 0)
            fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
            val failing = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == Path(home, "version.json") && content == "\"0.7.0\"") throw IOException("injected version write failure")
                    fs.writeString(path, content, append, mustCreate)
                }
            }
            assertFailsWith<IOException> { upgrade(home, failing) }
            assertEquals("\"0.5.2\"", fs.readString(Path(home, "version.json")))
            upgrade(home, noRepublish(home))
            fs.writeString(Path(home, "sessions", "0", "settings", "0.json"), "invalid mirror")
            database(home) { it.execute("UPDATE sessions SET archived=1") }
            upgrade(home, noRepublish(home))
            database(home) { assertEquals(listOf(listOf(1L)), it.query("SELECT archived FROM sessions")) }
        }

        test("manifest rejects staging payload corruption and preserves the previously published database") { home ->
            populatedSession(home, 0)
            migrateToV0_7_0(home, fs)
            // Change the source so the old, valid database must be rebuilt.
            record(home, 0, "settings", 7, Settings.replace("migration", "changed"))
            fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
            val before = snapshot(Path(home, "sessions"))
            var reads = 0
            var injected = false
            val corrupting = object : CoroutineFileSystem by fs {
                override suspend fun readBytes(path: Path, maxByteCount: Long): ByteArray {
                    if (path.name == "0.json" && path.parent?.name == "index") {
                        reads++
                        // Scan, old database verification, import, then staging verification.
                        if (reads == 4) {
                            databaseAt(Path(home, Staging)) { db ->
                                db.execute("""UPDATE agent_commits SET payload_json=json_set(payload_json,'$.future_top',99) WHERE type='user_message' AND "index"=0""")
                            }
                            injected = true
                        }
                    }
                    return fs.readBytes(path, maxByteCount)
                }
            }
            assertFailsWith<IOException> { upgrade(home, corrupting) }
            assertTrue(injected)
            assertEquals(before, snapshot(Path(home, "sessions")))
            assertEquals("\"0.5.2\"", fs.readString(Path(home, "version.json")))
            database(home) { db ->
                assertEquals(listOf(listOf("migration")), db.query("SELECT thread_name FROM session_catalog"))
            }
            assertNoStaging(home)
            upgrade(home)
            database(home) { db ->
                assertEquals(listOf(listOf("changed")), db.query("SELECT thread_name FROM session_catalog"))
            }
        }

        val mismatches = linkedMapOf(
            "archived" to "UPDATE sessions SET archived=1",
            "sparse index set with same count and maximum" to """UPDATE agent_commits SET "index"=1 WHERE type='user_message' AND "index"=0""",
            "payload unknown field" to "UPDATE agent_commits SET payload_json=json_set(payload_json,'$.future',99) WHERE type='settings'",
            "missing timeline row" to """DELETE FROM agent_commits WHERE type='text_tool_event' AND "index"=0""",
            "extra session" to "INSERT INTO sessions VALUES(1,0)",
            "catalog view" to "DROP VIEW session_catalog",
        )
        for ((label, mutation) in mismatches) {
            test("published database with mismatched $label is completely rebuilt") { home ->
                populatedSession(home, 0)
                migrateToV0_7_0(home, fs)
                database(home) { db ->
                    db.execute(mutation)
                    if (label == "catalog view") db.execute("CREATE VIEW session_catalog AS SELECT session_id, archived, 'wrong' AS thread_name FROM sessions")
                }
                var published = false
                val observing = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (destination == Path(home, "kodex.db")) published = true
                        fs.atomicMove(source, destination)
                    }
                }
                migrateToV0_7_0(home, observing)
                assertTrue(published)
                migrateToV0_7_0(home, noRepublish(home))
                assertNoStaging(home)
            }
        }

        test("corrupt existing database is replaced only after successful source validation") { home ->
            populatedSession(home, 0)
            fs.writeString(Path(home, "kodex.db"), "not SQLite")
            migrateToV0_7_0(home, fs)
            migrateToV0_7_0(home, noRepublish(home))
        }

        for ((timeline, invalid) in listOf(
            "index" to "{broken",
            "work" to "[broken",
            "settings" to "[]",
            "timestamp" to "\"not an instant\"",
            "timestamp" to "{\"epoch_seconds\":0,\"nanoseconds\":0}",
            "token-count" to "not-json",
            "unstable" to "{broken",
        )) {
            test("rejects corrupt or incompatible $timeline record $invalid without changing source version or database") { home ->
                populatedSession(home, 0)
                migrateToV0_7_0(home, fs)
                val published = fs.readBytes(Path(home, "kodex.db"))
                fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
                record(home, 0, timeline, 7, invalid)
                val before = snapshot(Path(home, "sessions"))
                assertFailsWith<IOException> { upgrade(home) }
                assertEquals(before, snapshot(Path(home, "sessions")))
                assertContentEquals(published, fs.readBytes(Path(home, "kodex.db")))
                assertEquals("\"0.5.2\"", fs.readString(Path(home, "version.json")))
                assertNoStaging(home)
            }
        }

        for (latest in listOf("6", "8", "-2", "null", "{}", "\"7\"", "07", "+7", "7.0", "broken")) {
            test("rejects invalid or inconsistent latest pointer $latest") { home ->
                populatedSession(home, 0)
                fs.writeString(Path(home, "sessions", "0", "index", "latest.json"), latest)
                assertRejected(home)
            }
        }
        for (entry in listOf("abc", "01", "-1", "+1", "2147483648", " 1")) {
            test("rejects noncanonical session directory $entry") { home ->
                fs.createDirectories(Path(home, "sessions", entry))
                assertRejected(home)
            }
        }
        test("rejects numeric non-directory session entry") { home ->
            fs.createDirectories(Path(home, "sessions"))
            fs.writeString(Path(home, "sessions", "0"), "not a directory")
            assertRejected(home)
        }
        test("rejects sessions root that is a file") { home ->
            fs.writeString(Path(home, "sessions"), "not a directory")
            assertRejected(home)
        }
        for (missing in listOf(true, false)) {
            test("rejects ${if (missing) "missing" else "non-directory"} timeline") { home ->
                emptySession(home, 0)
                removeTree(Path(home, "sessions", "0", "unstable"))
                if (!missing) fs.writeString(Path(home, "sessions", "0", "unstable"), "file")
                assertRejected(home)
            }
        }
    }
}

private val fs = SystemCoroutineFileSystem
private const val Staging = ".kodex-migration-0.7.0-staging.db"
private val Names = linkedMapOf("index" to "user_message", "work" to "text_tool_event", "settings" to "settings", "timestamp" to "timestamp", "token-count" to "token_count_response", "unstable" to "unstable")
private const val Settings = """{"model":"synthetic","threadName":"migration","cwd":"/synthetic","future":{"nested":[1,null,"opaque"]}}"""
private const val Index = """{"type":"user_message","content":[{"type":"input_text","text":"hello","future_nested":9}],"future_top":8}"""
private const val Work = """{"type":"text_tool_event","call_id":"c","name":"test","arguments":{"opaque":"{unparsed}","z":99},"result":"OK","success":true,"future":42}"""
private const val TokenCount = """{"kind":"response","total_tokens":42,"future":{"retained":true}}"""
private const val Unstable = """[{"type":"custom","call_id":"pending","name":"test","input":"opaque","future":{"retained":true}}]"""
private const val Created = "\"2026-10-25T12:00:00.123456789Z\""
private const val Updated = "\"2026-10-25T12:00:07.987654321Z\""

private suspend fun temporaryHome(): Path = Path(SystemTemporaryDirectory, "kodex-migration-070 space-${Random.nextLong()}").also { fs.createDirectories(it) }
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
    for ((timeline, payload) in listOf("index" to Index, "work" to Work, "settings" to Settings, "token-count" to TokenCount, "unstable" to Unstable)) {
        record(home, index, timeline, 0, payload)
        record(home, index, timeline, 7, payload)
    }
    record(home, index, "timestamp", 0, Created)
    record(home, index, "timestamp", 7, Updated)
}
private suspend fun snapshot(root: Path): Map<String, List<Byte>> {
    val result = linkedMapOf<String, List<Byte>>()
    suspend fun visit(path: Path, relative: String) {
        val metadata = fs.metadataOrNull(path) ?: return
        if (metadata.isDirectory) {
            result["$relative/"] = emptyList()
            fs.list(path).forEach { visit(it, if (relative.isEmpty()) it.name else "$relative/${it.name}") }
        } else result[relative] = fs.readBytes(path).toList()
    }
    visit(root, "")
    return result
}
private suspend fun assertNoStaging(home: Path) {
    for (suffix in listOf("", "-wal", "-shm")) assertFalse(fs.exists(Path(home, Staging + suffix)))
}
private suspend fun assertRollbackJournal(path: Path) {
    // Inspect before reopening: the driver's bootstrap may switch a new connection to WAL.
    val header = fs.readBytes(path)
    assertEquals(1.toByte(), header[18])
    assertEquals(1.toByte(), header[19])
}
private inline fun databaseAt(path: Path, block: (SqliteConnection) -> Unit) =
    openSqliteConnection(path).use(block)
private inline fun database(home: Path, block: (SqliteConnection) -> Unit) = databaseAt(Path(home, "kodex.db"), block)
private fun noRepublish(home: Path): CoroutineFileSystem = object : CoroutineFileSystem by fs {
    override suspend fun atomicMove(source: Path, destination: Path) {
        check(destination != Path(home, "kodex.db")) { "Matching database must be reused" }
        fs.atomicMove(source, destination)
    }
}
private suspend fun CoroutineScope.upgrade(home: Path, fileSystem: CoroutineFileSystem = fs) {
    prepareKodexHome(home, MigrationVersion("0.7.0"), KodexHomeMigrations, fileSystem).closeAndJoin()
}
private suspend fun <T> io.github.stream29.kodex.app.migration.KodexHomeHandle.useHandle(
    block: suspend (io.github.stream29.kodex.app.migration.KodexHomeHandle) -> T,
): T = CoroutineCloseable(::closeAndJoin).use { block(this) }
private suspend fun CoroutineScope.assertRejected(home: Path) {
    fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
    val before = snapshot(Path(home, "sessions"))
    assertFailsWith<IOException> { upgrade(home) }
    assertEquals(before, snapshot(Path(home, "sessions")))
    assertEquals("\"0.5.2\"", fs.readString(Path(home, "version.json")))
    assertFalse(fs.exists(Path(home, "kodex.db")))
    assertNoStaging(home)
}
