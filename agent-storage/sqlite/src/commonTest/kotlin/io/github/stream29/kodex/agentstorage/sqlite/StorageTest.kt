package io.github.stream29.kodex.agentstorage.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.utils.sqlite.*
import kotlin.test.*
import kotlin.time.Instant

private fun seeded(): SqliteConnection = openInMemorySqliteConnection().also {
    ensureSqlitePhaseOneSchema(it)
    it.execute("INSERT INTO sessions VALUES(0,0)")
    it.execute("INSERT INTO agents VALUES(0,0,NULL)")
}

private suspend fun checkSparseFamily(
    storage: SqliteAgentStorage,
    types: Set<AgentCommitType>,
    first: AgentCommit,
    second: AgentCommit,
) {
    storage.revert(0)
    assertEquals(-1, storage.latestIndex())
    assertTrue(storage.getCommitsExact(0, types).isEmpty())
    assertNull(storage.floorToIndex(8, types))
    assertNull(storage.ceilToIndex(0, types))

    storage.commit(CommitTimestamp(Instant.fromEpochSeconds(0))) // 0
    storage.commit(CommitTimestamp(Instant.fromEpochSeconds(1))) // 1
    val firstIndex = storage.commit(first) // 2
    repeat(6) { step ->
        storage.commit(CommitTimestamp(Instant.fromEpochSeconds(3L + step)))
    }
    val secondIndex = storage.commit(second) // 9

    assertEquals(2, firstIndex)
    assertEquals(9, secondIndex)
    assertEquals(9, storage.latestIndex())
    assertEquals(listOf(first), storage.getCommitsExact(2, types))
    assertTrue(storage.getCommitsExact(8, types).isEmpty())
    assertEquals(2, storage.floorToIndex(8, types))
    assertEquals(9, storage.ceilToIndex(8, types))
    assertEquals(
        listOf(IndexedAgentCommit(2, first), IndexedAgentCommit(9, second)),
        storage.getCommitsIn(0..99, types),
    )
    assertEquals(
        listOf(IndexedAgentCommit(2, first)),
        storage.getFirstCommitsIn(0..99, types, limit = 1),
    )
    assertEquals(
        listOf(IndexedAgentCommit(9, second)),
        storage.getLastCommitsIn(0..99, types, limit = 1),
    )
    assertTrue(storage.getCommitsIn(8..3, types).isEmpty())
    storage.revert(9)
    assertEquals(8, storage.latestIndex())
    assertEquals(2, storage.floorToIndex(8, types))
    storage.revert(0)
    assertEquals(-1, storage.latestIndex())
}

val sqliteStorageTest by testSuite {
    test("all commit families retain change point semantics and fine-grained commit types") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            assertEquals("db:agent/0", storage.uri)
            checkSparseFamily(
                storage,
                AgentCommitType.IndexTypes,
                StableUserMessage(listOf(ContentItem.InputText("a"))),
                StableAssistantMessage(listOf(ContentItem.OutputText("b")), phase = MessagePhase.FinalAnswer),
            )
            checkSparseFamily(
                storage,
                AgentCommitType.WorkTypes,
                StableWebSearchCall(ResponseItem.WebSearchCall(status = "completed")),
                StableWebSearchCall(ResponseItem.WebSearchCall(status = "failed")),
            )
            checkSparseFamily(
                storage,
                AgentCommitType.SettingsTypes,
                KodexAgentSettings(model = OpenAiModelId("a")),
                KodexAgentSettings(model = OpenAiModelId("b")),
            )
            checkSparseFamily(
                storage,
                AgentCommitType.TokenCountTypes,
                TokenCountSnapshot.Initialization(0),
                TokenCountSnapshot.Response(42),
            )
            checkSparseFamily(
                storage,
                AgentCommitType.UnstableTypes,
                UnstableSnapshot(emptyList()),
                UnstableSnapshot(emptyList()),
            )

            storage.commit(TokenCountSnapshot.Legacy(1)) // 0
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("u")))) // 1
            storage.commit(TokenCountSnapshot.Initialization(2)) // 2
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("c")), phase = MessagePhase.Commentary)) // 3
            storage.commit(TokenCountSnapshot.Compaction(3)) // 4
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("f")), phase = MessagePhase.FinalAnswer)) // 5
            storage.commit(TokenCountSnapshot.Response(4)) // 6
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("n")), phase = null)) // 7

            assertEquals(
                listOf(
                    listOf(0L, "token_count_legacy"),
                    listOf(1L, "user_message"),
                    listOf(2L, "token_count_initialization"),
                    listOf(3L, "assistant_message_commentary"),
                    listOf(4L, "token_count_compaction"),
                    listOf(5L, "assistant_message_final"),
                    listOf(6L, "token_count_response"),
                    listOf(7L, "assistant_message"),
                ),
                db.query("""SELECT "index", type FROM agent_commits WHERE agent_id = 0 ORDER BY "index", type"""),
            )
        }
    }

    test("injected SQLite failure rolls back entire multi-commit batch and revert") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            db.execute("CREATE TRIGGER reject_token BEFORE INSERT ON agent_commits WHEN NEW.type = 'token_count_initialization' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<SqliteFailure> {
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test")))
            }
            // Neither settings nor timestamp should remain if token_count_initialization failed in the batch!
            assertEquals(-1, storage.latestIndex())
            assertTrue(db.query("SELECT * FROM agent_commits WHERE agent_id = 0").isEmpty())

            db.execute("DROP TRIGGER reject_token")
            storage.initialize(KodexAgentSettings(model = OpenAiModelId("test")))
            assertEquals(0, storage.latestIndex())

            db.execute("CREATE TRIGGER reject_revert BEFORE DELETE ON agent_commits WHEN OLD.type = 'timestamp' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<SqliteFailure> { storage.revert(0) }
            assertNotNull(storage.getTimestampExact(0))
            assertNotNull(storage.getSettingsExact(0))
        }
    }

    test("COMMIT failure rolls back commit batch") {
        seeded().use { db ->
            try {
                val storage = SqliteAgentStorage(db, 0)
                db.setAuthorizer { _, operation, _, _, _ -> operation != "COMMIT" }
                assertFailsWith<SqliteFailure> {
                    storage.commit(CommitTimestamp(Instant.fromEpochSeconds(1)))
                }
                db.setAuthorizer(null)
                assertEquals(-1, storage.latestIndex())
                storage.commit(CommitTimestamp(Instant.fromEpochSeconds(2)))
                assertEquals(0, storage.latestIndex())
            } finally {
                db.setAuthorizer(null)
            }
        }
    }

    test("ensure schema initializes empty databases and rejects incomplete or unsupported schema") {
        openInMemorySqliteConnection().use { db ->
            ensureSqlitePhaseOneSchema(db)
            ensureSqlitePhaseOneSchema(db)
            db.execute("DROP VIEW session_catalog")
            assertFailsWith<IllegalStateException> { ensureSqlitePhaseOneSchema(db) }
            db.execute("PRAGMA user_version=2")
            assertFailsWith<IllegalStateException> { ensureSqlitePhaseOneSchema(db) }
        }
    }

    test("getFirstCommitsIn and getLastCommitsIn apply AgentCommitType ordinal ordering before limit") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            val message = StableUserMessage(listOf(ContentItem.InputText("hello")))
            val timestamp = CommitTimestamp(Instant.fromEpochSeconds(10))
            val index = storage.commit(listOf(timestamp, message))
            assertEquals(0, index)
            assertEquals(
                listOf(IndexedAgentCommit(0, message)),
                storage.getFirstCommitsIn(0..0, setOf(AgentCommitType.UserMessage, AgentCommitType.Timestamp), limit = 1),
            )
            assertEquals(
                listOf(IndexedAgentCommit(0, timestamp)),
                storage.getLastCommitsIn(0..0, setOf(AgentCommitType.UserMessage, AgentCommitType.Timestamp), limit = 1),
            )
        }
    }

    test("rejects negative agent id") {
        seeded().use { db ->
            assertFailsWith<IllegalArgumentException> { SqliteAgentStorage(db, -1) }
        }
    }
}
