package io.github.stream29.kodex.agentstorage.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.utils.sqlite.*
import kotlin.test.*
import kotlin.time.Instant

private fun seeded(): SqliteConnection = openInMemorySqliteConnection().also {
    ensureSqlitePhaseOneSchema(it)
    it.execute("INSERT INTO sessions VALUES(0,0)")
    it.execute("INSERT INTO agents VALUES(0,0,NULL)")
}
private suspend fun <T> sparse(timeline: MutableIndexVersioned<T>, first: T, second: T) {
    assertEquals(-1, timeline.latestIndex())
    assertNull(timeline.getExact(0))
    assertNull(timeline.floorToIndex(8))
    assertNull(timeline.ceilToIndex(0))
    timeline[2] = first
    timeline[9] = second
    assertEquals(9, timeline.latestIndex())
    assertEquals(first, timeline[8])
    assertEquals(second, timeline[99])
    assertNull(timeline.getExact(8))
    assertEquals(2, timeline.floorToIndex(8))
    assertEquals(9, timeline.ceilToIndex(8))
    assertEquals(listOf(2, 9), timeline.indexesIn(0..Int.MAX_VALUE))
    assertEquals(listOf(2 to first, 9 to second), timeline.valuesIn(0..99))
    assertTrue(timeline.valuesIn(8..3).isEmpty())
    assertFailsWith<IllegalArgumentException> { timeline[9] = first }
    assertFailsWith<IllegalArgumentException> { timeline[0] }
    timeline.revert(9)
    assertEquals(2, timeline.latestIndex())
    timeline.revert(0)
    assertEquals(-1, timeline.latestIndex())
}
val sqliteStorageTest by testSuite {
    test("all six sparse timelines retain change point semantics") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            assertEquals("db:agent/0", storage.uri)
            sparse(storage.index, StableUserMessage(listOf(ContentItem.InputText("a"))), StableUserMessage(listOf(ContentItem.InputText("b"))))
            sparse(storage.work, StableWebSearchCall(ResponseItem.WebSearchCall(status = "completed")), StableWebSearchCall(ResponseItem.WebSearchCall(status = "failed")))
            sparse(storage.settings, KodexAgentSettings(model = OpenAiModelId("a")), KodexAgentSettings(model = OpenAiModelId("b")))
            sparse(storage.timestamp, Instant.fromEpochSeconds(-2, 5), Instant.fromEpochSeconds(4, 999999999))
            sparse(storage.tokenCount, TokenCountSnapshot(TokenCountKind.Initialization, 0), TokenCountSnapshot(TokenCountKind.Response, 42))
            sparse(storage.unstable, emptyList(), emptyList())
        }
    }
    test("injected SQLite failure rolls back single timeline write and revert") {
        seeded().use { db ->
            val storage = SqliteAgentStorage(db, 0)
            db.execute("CREATE TRIGGER reject_token BEFORE INSERT ON agent_commits WHEN NEW.timeline = 'token_count' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<SqliteFailure> { storage.tokenCount[0] = TokenCountSnapshot(TokenCountKind.Initialization, 0) }
            assertEquals(-1, storage.tokenCount.latestIndex())
            db.execute("DROP TRIGGER reject_token")
            storage.initialize(KodexAgentSettings(model = OpenAiModelId("test")))
            db.execute("CREATE TRIGGER reject_revert BEFORE DELETE ON agent_commits WHEN OLD.timeline = 'timestamp' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<SqliteFailure> { storage.timestamp.revert(0) }
            assertNotNull(storage.timestamp.getExact(0))
        }
    }
    test("COMMIT failure rolls back timeline write") {
        seeded().use { db ->
            try {
                val storage = SqliteAgentStorage(db, 0)
                db.setAuthorizer { _, operation, _, _, _ -> operation != "COMMIT" }
                assertFailsWith<SqliteFailure> {
                    storage.timestamp[0] = Instant.fromEpochSeconds(1)
                }
                db.setAuthorizer(null)
                assertEquals(-1, storage.latestIndex())
                storage.timestamp[0] = Instant.fromEpochSeconds(2)
                assertEquals(0, storage.latestIndex())
            } finally { db.setAuthorizer(null) }
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
    test("rejects negative agent id") {
        seeded().use { db ->
            assertFailsWith<IllegalArgumentException> { SqliteAgentStorage(db, -1) }
        }
    }
}
