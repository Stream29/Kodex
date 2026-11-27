package io.github.stream29.kodex.agentstorage.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant

private fun seeded(): SqliteConnection = openInMemorySqliteConnection().also {
    initializeKodexHomeDatabase(it)
    it.execute("INSERT INTO sessions VALUES(0)")
    it.execute("INSERT INTO agents(agent_id, session_id) VALUES(0,0)")
    it.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(0)")
}

val sqliteSubagentEventTest by testSuite {
    test("after reopening, creation and completion stay on the direct parent's correct timelines") {
        val path = Path(SystemTemporaryDirectory, "kodex-subagent-events-${Random.nextLong().toULong()}.db")
        val spawn = StableSubagentCreatedEvent(92)
        val completion = StableSubagentCompletedEvent(92)
        try {
            openSqliteConnection(path).use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions(session_id) VALUES(12), (90)")
                db.execute("""INSERT INTO agents(agent_id, session_id, parent_agent_id)
                    VALUES(90, 12, NULL), (91, 12, 90), (92, 12, 91), (12, 90, NULL)""")
                db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(12), (90)")
                val directParent = SqliteAgentStorage(db, 91)
                assertEquals(0, directParent.commit(spawn))
                // Rows produced before reclassification used the same wire name and payload.
                val oldPayload = """{"type":"event_subagent_completed","child_agent_id":92}"""
                db.execute(
                    """INSERT INTO agent_commits VALUES(91,'event_subagent_completed',1,?,'2026-11-19T00:00:00Z'),
                        (91,'steer',2,?,'2026-11-19T00:00:00Z')""",
                    listOf(oldPayload, "[$oldPayload]"),
                )
            }
            openSqliteConnection(path).use { db ->
                val parent = SqliteAgentStorage(db, 91)
                assertEquals(spawn, parent.getCommitExact(0, AgentCommitType.IndexTypes)?.commit)
                assertEquals(completion, parent.getCommitExact(1, AgentCommitType.IndexTypes)?.commit)
                assertNull(parent.getCommitExact(1, AgentCommitType.WorkTypes))
                assertEquals(listOf(completion), parent.getSteerAt(2))
                for (elsewhere in listOf(12, 90, 92)) {
                    assertEquals(-1, SqliteAgentStorage(db, elsewhere).latestIndex())
                }
            }
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}

private suspend fun checkSparseFamily(
    storage: SqliteAgentStorage,
    types: Set<AgentCommitType>,
    first: AgentCommit,
    second: AgentCommit,
    spacer: AgentCommit,
) {
    storage.revert(0)
    assertEquals(-1, storage.latestIndex())
    assertNull(storage.getCommitExact(0, types))
    assertEquals(0, storage.countCommitsIn(0..8, types))
    assertNull(storage.getCommitAtOrBefore(8, types))
    assertNull(storage.getCommitAtOrAfter(0, types))

    val t0 = Instant.fromEpochSeconds(0)
    val t1 = Instant.fromEpochSeconds(1)
    val t2 = Instant.fromEpochSeconds(2)
    val t9 = Instant.fromEpochSeconds(9)
    storage.commit(listOf(spacer), t0) // 0
    storage.commit(listOf(spacer), t1) // 1
    val firstIndex = storage.commit(listOf(first), t2) // 2
    repeat(6) { step ->
        val t = Instant.fromEpochSeconds(3L + step)
        storage.commit(listOf(spacer), t)
    }
    val secondIndex = storage.commit(listOf(second), t9) // 9

    assertEquals(2, firstIndex)
    assertEquals(9, secondIndex)
    assertEquals(9, storage.latestIndex())
    assertEquals(IndexedAgentCommit(2, first, t2), storage.getCommitExact(2, types))
    assertNull(storage.getCommitExact(8, types))
    assertEquals(1, storage.countCommitsIn(0..8, types))
    assertEquals(2, storage.countCommitsIn(0..99, types))
    assertEquals(IndexedAgentCommit(2, first, t2), storage.getCommitAtOrBefore(8, types))
    assertEquals(IndexedAgentCommit(9, second, t9), storage.getCommitAtOrAfter(8, types))
    assertEquals(
        listOf(IndexedAgentCommit(2, first, t2), IndexedAgentCommit(9, second, t9)),
        storage.getCommitsIn(0..99, types),
    )
    assertEquals(
        listOf(IndexedAgentCommit(2, first, t2)),
        storage.getFirstCommitsIn(0..99, types, limit = 1),
    )
    assertEquals(
        listOf(IndexedAgentCommit(9, second, t9)),
        storage.getLastCommitsIn(0..99, types, limit = 1),
    )
    assertTrue(storage.getCommitsIn(8..3, types).isEmpty())
    storage.revert(9)
    assertEquals(8, storage.latestIndex())
    assertEquals(IndexedAgentCommit(2, first, t2), storage.getCommitAtOrBefore(8, types))
    storage.revert(0)
    assertEquals(-1, storage.latestIndex())
}

val sqliteStorageTest by testSuite {
    test("timeline metadata refreshes from durable state; only destructive reverts increment the nonce") {
        val path = Path(SystemTemporaryDirectory, "kodex-storage-nonce-${Random.nextLong().toULong()}.db")
        try {
            openSqliteConnection(path).use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions(session_id) VALUES (0)")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES (0, 0)")
                openExistingSqliteConnection(path).use { reader ->
                    val observer = SqliteAgentStorage(reader, 0)
                    val seen = mutableListOf<Triple<Int, Boolean, Long>>()
                    lateinit var store: SqliteAgentStorage
                    store = SqliteAgentStorage(db, 0, onHistoryChanged = { types ->
                        val nonce = reader.query("SELECT cache_nonce FROM agents WHERE agent_id=0").single().single() as Long
                        seen += Triple(store.latestIndexFlow.value, types == null, nonce)
                    })
                    assertEquals(1L, store.cacheNonce())
                    assertEquals(-1, store.latestIndexFlow.value)
                    store.commit(SteerSnapshot(listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))))
                    store.commit(SteerSnapshot(emptyList()))
                    assertEquals(1L, store.cacheNonce())

                    store.revert(2) // Clears the older non-empty snapshot without deleting a row.
                    assertEquals(2L, store.cacheNonce.value)
                    assertEquals(1, store.latestIndexFlow.value)
                    assertTrue(store.getSteerAt(0).isEmpty())
                    store.revert(2) // Both remaining snapshots are already empty.
                    assertEquals(2L, observer.cacheNonce()) // Direct read sees the database, not the old snapshot.
                    assertEquals(1L, observer.cacheNonce.value)
                    observer.refreshObservableState()
                    assertEquals(2L, observer.cacheNonce.value)
                    assertEquals(1, observer.latestIndexFlow.value)

                    store.revert(1)
                    assertEquals(3L, store.cacheNonce())
                    assertEquals(0, store.latestIndexFlow.value)
                    assertEquals(
                        listOf(Triple(0, false, 1L), Triple(1, false, 1L), Triple(1, true, 2L), Triple(0, true, 3L)),
                        seen,
                    )
                }
            }
            openExistingSqliteConnection(path).use { db ->
                val reopened = SqliteAgentStorage(db, 0)
                assertEquals(3L, reopened.cacheNonce.value)
                assertEquals(0, reopened.latestIndexFlow.value)
            }
        } finally {
            for (candidate in listOf(path, Path("$path-wal"), Path("$path-shm"))) {
                SystemFileSystem.delete(candidate, mustExist = false)
            }
        }
    }

    test("a failed nonce increment rolls back the timeline and emits no revert callback") {
        seeded().use { db ->
            db.execute("UPDATE agents SET cache_nonce=? WHERE agent_id=0", listOf(Long.MAX_VALUE))
            var callbacks = 0
            val store = SqliteAgentStorage(db, 0, onHistoryChanged = { callbacks++ })
            store.commit(SteerSnapshot(emptyList()))
            assertFailsWith<SqliteFailure> { store.revert(0) }
            assertEquals(1, callbacks)
            assertEquals(0, store.latestIndex())
            assertEquals(0, store.latestIndexFlow.value)
            assertEquals(Long.MAX_VALUE, store.cacheNonce())
        }
    }

    test("all commit families retain change point semantics and fine-grained commit types") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            assertEquals(0, storage.agentId)
            val askSpacer = AskUserSnapshot(emptyList())
            checkSparseFamily(
                storage,
                AgentCommitType.IndexTypes,
                StableUserMessage(listOf(ContentItem.InputText("a"))),
                StableAssistantMessage(listOf(ContentItem.OutputText("b")), phase = MessagePhase.FinalAnswer),
                askSpacer,
            )
            checkSparseFamily(
                storage,
                AgentCommitType.WorkTypes,
                StableWebSearchCall(ResponseItem.WebSearchCall(status = "completed")),
                StableWebSearchCall(ResponseItem.WebSearchCall(status = "failed")),
                askSpacer,
            )
            checkSparseFamily(
                storage,
                AgentCommitType.SettingsTypes,
                KodexAgentSettings(model = OpenAiModelId("a")),
                KodexAgentSettings(model = OpenAiModelId("b")),
                askSpacer,
            )
            checkSparseFamily(
                storage,
                AgentCommitType.LlmRequestMetaTypes,
                LlmRequestMeta.Initialization(0),
                LlmRequestMeta.Response(42, elapsedNanoseconds = 10_000_000L, outputTokens = 6),
                askSpacer,
            )
            checkSparseFamily(
                storage,
                AgentCommitType.UnstableTypes,
                UnstableSnapshot(emptyList()),
                UnstableSnapshot(emptyList()),
                askSpacer,
            )

            storage.commit(LlmRequestMeta.Legacy(1)) // 0
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("u")))) // 1
            storage.commit(LlmRequestMeta.Initialization(2)) // 2
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("c")), phase = MessagePhase.Commentary)) // 3
            storage.commit(LlmRequestMeta.Compaction(3)) // 4
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("f")), phase = MessagePhase.FinalAnswer)) // 5
            storage.commit(LlmRequestMeta.Response(4, elapsedNanoseconds = 1_000_000L, outputTokens = 1)) // 6
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("n")), phase = null)) // 7

            assertEquals(
                listOf(
                    listOf(0L, "llm_request_meta_legacy"),
                    listOf(1L, "user_message"),
                    listOf(2L, "llm_request_meta_initialization"),
                    listOf(3L, "assistant_message_commentary"),
                    listOf(4L, "llm_request_meta_compaction"),
                    listOf(5L, "assistant_message_final"),
                    listOf(6L, "llm_request_meta_response"),
                    listOf(7L, "assistant_message"),
                ),
                db.query("""SELECT "index", type FROM agent_commits WHERE agent_id = 0 ORDER BY "index", type"""),
            )
        }
    }

    test("injected SQLite failure rolls back entire multi-commit batch and revert") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            db.execute("CREATE TRIGGER reject_token BEFORE INSERT ON agent_commits WHEN NEW.type = 'llm_request_meta_initialization' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<SqliteFailure> {
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test")))
            }
            assertEquals(-1, storage.latestIndex())
            assertTrue(db.query("SELECT * FROM agent_commits WHERE agent_id = 0").isEmpty())

            db.execute("DROP TRIGGER reject_token")
            storage.initialize(KodexAgentSettings(model = OpenAiModelId("test")))
            assertEquals(0, storage.latestIndex())

            db.execute("CREATE TRIGGER reject_revert BEFORE DELETE ON agent_commits WHEN OLD.type = 'settings' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<SqliteFailure> { storage.revert(0) }
            assertNotNull(storage.getCommitExact(0, AgentCommitType.SettingsTypes))
        }
    }

    test("schema verification rejects incomplete or unsupported schema") {
        openInMemorySqliteConnection().use { db ->
            initializeKodexHomeDatabase(db)
            requireKodexHomeDatabase(db)
            db.execute("DROP TABLE session_catalog_view")
            assertFailsWith<IllegalStateException> { requireKodexHomeDatabase(db) }
            db.execute("PRAGMA user_version=99")
            assertFailsWith<IllegalStateException> { requireKodexHomeDatabase(db) }
        }
    }

    test("getFirstCommitsIn and getLastCommitsIn apply AgentCommitType ordinal ordering before limit") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            val message = StableUserMessage(listOf(ContentItem.InputText("hello")))
            val settings = KodexAgentSettings(model = OpenAiModelId("test"))
            val t10 = Instant.fromEpochSeconds(10)
            val index = storage.commit(listOf(settings, message), t10)
            assertEquals(0, index)
            assertEquals(
                listOf(IndexedAgentCommit(0, message, t10)),
                storage.getFirstCommitsIn(0..0, setOf(AgentCommitType.UserMessage, AgentCommitType.Settings), limit = 1),
            )
            assertEquals(
                listOf(IndexedAgentCommit(0, settings, t10)),
                storage.getLastCommitsIn(0..0, setOf(AgentCommitType.UserMessage, AgentCommitType.Settings), limit = 1),
            )
        }
    }

    test("rejects negative agent id") {
        seeded().use { db ->
            assertFailsWith<IllegalArgumentException> { SqliteAgentStorage(db, -1) }
        }
    }
}
