package io.github.stream29.kodex.app.migration.v0_7_2

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.ext.activeMessageWindowAt
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
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
                assertTrue("'read_media_tool_event'" in sql)
                assertFalse("'image_view_tool_event'" in sql)
                assertFalse("'request_user_input_tool_event'" in sql)
                assertFalse("'mcp_tool_event'" in sql)
                assertFalse("'timestamp'" in sql)
                assertFalse("'token_count_response'" in sql)
                val scheduleColumns = db.query("PRAGMA table_info(schedules)").mapNotNull { it.getOrNull(1) as? String }
                assertFalse("trigger_at" in scheduleColumns)
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
                        IndexCommit.serializer(),
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

        test("mirror sync interruption after in-place SQLite commit recovers on reentry without re-migrating database") { home ->
            emptySession(home, 0)
            record(home, 0, "settings", 0, Settings)
            record(home, 0, "timestamp", 0, Created)
            record(home, 0, "index", 1, UserOne)
            record(home, 0, "work", 2, LegacyCompactionOne)
            migrateToV0_7_0(home, fs)
            migrateToV0_7_1(home, fs)
            record(home, 0, "index", 2, LegacyCompactionOne)
            fs.writeString(Path(home, "version.json"), "\"0.7.1\"")
            val targetMirror = Path(home, "sessions", "0", "index", "2.json")
            val failing = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == targetMirror) {
                        assertRollbackJournal(Path(home, "kodex.db"))
                        throw IOException("injected 0.7.2 mirror sync failure")
                    }
                    fs.writeString(path, content, append, mustCreate)
                }
            }
            assertFailsWith<IOException> { upgrade(home, failing) }
            assertTrue(fs.exists(Path(home, "kodex.db")))
            assertEquals("\"0.7.1\"", fs.readString(Path(home, "version.json")))
            assertNoStaging(home)
            upgrade(home)
            assertEquals("\"0.7.2\"", fs.readString(Path(home, "version.json")))
            val mirrorTwo = OpenAiJsonCodec.decodeFromString(
                IndexCommit.serializer(),
                fs.readString(targetMirror),
            ) as StableContextCompaction
            assertEquals(
                listOf(StableUserMessage(listOf(ContentItem.InputText("first user")))),
                mirrorTwo.retainedItems,
            )
        }

        test("migrates all 7 retired tool events, tool_search invalid calls, unstable pending ask, fork/revert, and settings.yml hooks") { home ->
            emptySession(home, 0)
            record(home, 0, "settings", 0, Settings)
            record(home, 0, "timestamp", 0, Created)
            migrateToV0_7_0(home, fs)
            migrateToV0_7_1(home, fs)
            val ts = "2026-10-25T12:00:01Z"
            database(home) { db ->
                fun put(index: Int, type: String, payload: String) {
                    db.execute("INSERT INTO agent_commits VALUES (0, ?, ?, ?, ?)", listOf(type, index.toLong(), payload, ts))
                }
                put(1, "user_message", UserOne)
                put(
                    2,
                    "request_user_input_tool_event",
                    """{"type":"request_user_input_tool_event","call_id":"ask-ok","item_id":"it-1","arguments":{"questions":[{"id":"q1","header":"H","question":"Q?"}]},"result":{"type":"answered","response":{"answers":{"q1":{"answers":["yes"]}}}}}""",
                )
                put(
                    3,
                    "request_user_input_tool_event",
                    """{"type":"request_user_input_tool_event","call_id":"ask-fail","arguments":{"questions":[]},"result":{"type":"failure","message":"cancelled"}}""",
                )
                put(
                    4,
                    "image_view_tool_event",
                    """{"type":"image_view_tool_event","call_id":"img-1","arguments":{"path":"a.png","detail":"original","environment_id":"local"},"result":{"type":"success","output":{"image_url":"data:image/png;base64,AA==","detail":"original"}}}""",
                )
                put(
                    5,
                    "suggest_subagent_task_tool_event",
                    """{"type":"suggest_subagent_task_tool_event","call_id":"sub-1","item_id":"it-2","arguments":{"tasks":[{"title":"T","prompt":"P"}]},"result":{"type":"completed","response":{"status":"accepted","created_session_indices":[1]}}}""",
                )
                put(
                    6,
                    "mcp_tool_event",
                    """{"type":"mcp_tool_event","call_id":"mcp-1","item_id":"it-3","name":"echo","namespace":"mcp__demo","arguments":{"x":1},"result":{"content":[{"type":"text","text":"ok"}]}}""",
                )
                put(
                    7,
                    "web_search_tool_event",
                    """{"type":"web_search_tool_event","call_id":"web-1","item_id":"it-4","commands":{"search_query":[{"q":"kotlin"}]},"result":{"type":"success","response":{"output":"found"}}}""",
                )
                put(
                    8,
                    "image_generation_tool_event",
                    """{"type":"image_generation_tool_event","call_id":"gen-1","item_id":"it-5","arguments":{"prompt":"cat"},"result":{"type":"failure","message":"quota"}}""",
                )
                put(
                    9,
                    "tool_search_event",
                    """{"type":"tool_search_event","call_id":"ts-1","item_id":"it-6","arguments":{"query":"mcp"},"result":{"type":"success","tools":[]}}""",
                )
                put(
                    10,
                    "invalid_tool_call",
                    """{"type":"invalid_tool_call","call_id":"inv-1","invocation":{"type":"tool_search","arguments":{"bad":true}},"message":"bad search"}""",
                )
                put(11, "context_compaction", LegacyCompactionOne)
                put(
                    12,
                    "unstable",
                    """[{"type":"request_user_input","call_id":"ask-pending","arguments":{"questions":[{"id":"q2","header":"H2","question":"Pending?"}]}}]""",
                )
            }
            fs.writeString(
                Path(home, "settings.yml"),
                """
                backend:
                  hooks:
                    - command: notify-send
                      types:
                        - stop_request_user_input
                        - stop_suggest_subagent
                    - command: drop-me
                      types:
                        - stop_suggest_subagent
                frontend:
                  hooks: []
                """.trimIndent(),
            )
            fs.writeString(Path(home, "version.json"), "\"0.7.1\"")

            upgrade(home)

            val settingsYaml = fs.readString(Path(home, "settings.yml"))
            assertTrue("ask_user_created" in settingsYaml)
            assertFalse("stop_request_user_input" in settingsYaml)
            assertFalse("stop_suggest_subagent" in settingsYaml)
            assertFalse("drop-me" in settingsYaml)

            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    val storage = repository.open(0).storage
                    val latest = storage.latestIndex()
                    val allCommits = storage.getCommitsIn(0..latest, AgentCommitType.All)
                    assertTrue(allCommits.isNotEmpty())

                    val askEvent = assertIs<StableAskUserEvent>(
                        storage.getCommitExact(2, AgentCommitType.IndexTypes)?.commit,
                    )
                    assertEquals(1, askEvent.askId)

                    val askFail = assertIs<StableTextTool>(
                        storage.getCommitExact(3, AgentCommitType.WorkTypes)?.commit,
                    )
                    assertEquals("request_user_input", askFail.name)
                    assertEquals(false, askFail.success)

                    val readMedia = assertIs<StableReadMedia>(
                        storage.getCommitExact(4, AgentCommitType.WorkTypes)?.commit,
                    )
                    assertEquals("a.png", readMedia.arguments.path)

                    for (idx in listOf(5, 6, 7, 9)) {
                        val jsonEvent = assertIs<StableJsonTool>(
                            storage.getCommitExact(idx, AgentCommitType.WorkTypes)?.commit,
                        )
                        kotlin.test.assertNull(jsonEvent.itemId)
                    }

                    val genFail = assertIs<StableTextTool>(
                        storage.getCommitExact(8, AgentCommitType.WorkTypes)?.commit,
                    )
                    assertEquals("imagegen", genFail.name)
                    kotlin.test.assertNull(genFail.itemId)

                    val invalidCall = assertIs<StableInvalidToolCall>(
                        storage.getCommitExact(10, AgentCommitType.WorkTypes)?.commit,
                    )
                    val fnInv = assertIs<InvalidToolInvocation.Function>(invalidCall.invocation)
                    assertEquals("tool_search", fnInv.name)

                    val compaction = assertIs<StableContextCompaction>(
                        storage.getCommitExact(11, AgentCommitType.IndexTypes)?.commit,
                    )
                    assertEquals(2, compaction.retainedItems.size)
                    assertIs<StableAskUserEvent>(compaction.retainedItems[1])

                    val askEntries = storage.getAskUsersAt(latest)
                    assertEquals(2, askEntries.size)
                    kotlin.test.assertNotNull(askEntries[0].answer)
                    kotlin.test.assertNull(askEntries[1].answer)
                    assertEquals(emptyList(), storage.getUnstableAtOrNull(latest))

                    val forkId = repository.createFork(0)
                    val forkStorage = repository.open(forkId).storage
                    assertEquals(2, forkStorage.getAskUsersAt(forkStorage.latestIndex()).size)
                    forkStorage.revert(11)
                    assertEquals(1, forkStorage.getAskUsersAt(forkStorage.latestIndex()).size)
                }
            }
        }

        test("upgrades 0.7.0 through 0.7.1 to 0.7.2 normalizing token_count rows dropping schedules.trigger_at and removing retired mirror dirs") { home ->
            for (session in listOf(2, 9, 15)) emptySession(home, session)
            fs.writeString(Path(home, "sessions", "9", "archive.mark"), "")
            record(home, 2, "settings", 0, Settings)
            record(home, 2, "timestamp", 0, Created)
            record(home, 2, "token-count", 0, """{"kind":"initialization","total_tokens":0}""")
            record(home, 2, "index", 1, UserOne)
            record(home, 2, "token-count", 1, """{"kind":"legacy","total_tokens":10}""")
            record(home, 2, "index", 2, AssistantOne)
            record(home, 2, "token-count", 2, """{"kind":"compaction","total_tokens":20}""")
            record(home, 2, "index", 7, UserTwo)
            record(home, 2, "settings", 7, Settings)
            record(home, 2, "timestamp", 7, Updated)
            record(home, 2, "token-count", 7, """{"kind":"response","total_tokens":42}""")
            migrateToV0_7_0(home, fs)
            migrateToV0_7_1(home, fs)
            database(home) { db ->
                db.execute(
                    """
                    CREATE TABLE schedules (
                        schedule_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (schedule_id >= 1),
                        agent_id INTEGER NOT NULL CHECK (agent_id BETWEEN 0 AND 2147483647),
                        trigger_at_epoch_seconds INTEGER NOT NULL,
                        trigger_at_nanoseconds INTEGER NOT NULL CHECK (trigger_at_nanoseconds BETWEEN 0 AND 999999999),
                        trigger_at TEXT NOT NULL,
                        cron TEXT,
                        description TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    ) STRICT
                    """.trimIndent(),
                )
                db.execute(
                    "INSERT INTO schedules VALUES (1, 2, 1763201100, 500000000, '2025-11-15T10:05:00.500Z', NULL, 'legacy', '2025-11-15T10:00:00Z')",
                )
            }
            fs.writeString(Path(home, "version.json"), "\"0.7.1\"")

            upgrade(home)
            assertEquals("\"0.7.2\"", fs.readString(Path(home, "version.json")))

            database(home) { db ->
                assertEquals(
                    listOf(
                        listOf(0L, "llm_request_meta_initialization"),
                        listOf(1L, "llm_request_meta_legacy"),
                        listOf(2L, "llm_request_meta_compaction"),
                        listOf(7L, "llm_request_meta_response"),
                    ),
                    db.query("""SELECT "index", type FROM agent_commits WHERE agent_id = 2 AND type LIKE 'llm_request_meta_%' ORDER BY "index""""),
                )
                assertTrue(db.query("SELECT 1 FROM agent_commits WHERE type LIKE 'token_count_%' OR type = 'timestamp'").isEmpty())
                val scheduleColumns = db.query("PRAGMA table_info(schedules)").mapNotNull { it.getOrNull(1) as? String }
                assertFalse("trigger_at" in scheduleColumns)
                assertEquals(listOf(listOf(1L, 1763201100L, 500000000L)), db.query("SELECT schedule_id, trigger_at_epoch_seconds, trigger_at_nanoseconds FROM schedules"))
            }
            assertFalse(fs.exists(Path(home, "sessions", "2", "timestamp")))
            assertFalse(fs.exists(Path(home, "sessions", "2", "token-count")))

            testKodexAgentDependencies().use { dependencies ->
                SqliteKodexSessionRepository(home, dependencies).use { repository ->
                    assertEquals(listOf(2, 9, 15), repository.entries.value)
                    assertEquals(listOf(2, 15), repository.listEntries(false).map { it.entryIndex })
                    assertEquals(listOf(2, 9, 15), repository.listEntries(true).map { it.entryIndex })
                    val storage = repository.open(2).storage
                    assertEquals("migration", storage.getSettingsAt(7).threadName)
                    assertEquals(LlmRequestMeta.Response(totalTokens = 42L), storage.getLlmRequestMetaAtOrNull(7))
                    assertEquals(Instant.parse("2026-10-25T12:00:00.123456789Z"), repository.getEntry(2).createdAt)
                    assertEquals(Instant.parse("2026-10-25T12:00:07.987654321Z"), repository.getEntry(2).lastActivityAt)
                    kotlin.test.assertNull(repository.getEntry(15).createdAt)
                }
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

private suspend fun CoroutineScope.upgrade(home: Path, fileSystem: CoroutineFileSystem = fs) {
    prepareKodexHome(home, MigrationVersion("0.7.2"), KodexHomeMigrations, fileSystem).closeAndJoin()
}
