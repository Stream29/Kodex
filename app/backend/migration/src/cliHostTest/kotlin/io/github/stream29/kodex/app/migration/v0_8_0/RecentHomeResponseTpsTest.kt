package io.github.stream29.kodex.app.migration.v0_8_0

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

private const val Response = "llm_request_meta_response"
private const val Done = "2026-11-23T04:00:00.000000001Z"
private const val Before = "2026-11-23T03:59:59.999999998Z"
private const val Epoch = "1970-01-01T00:00:00Z"

public val recentHomeResponseTpsTest by testSuite {
    test("use the strict same-Agent predecessor, skip epoch and equal time, and keep exact nanoseconds and zero output") {
        openInMemorySqliteConnection().use { db ->
            db.installResponseFixture()
            db.appendCommit(7, 1, Before)
            db.appendCommit(7, 1, "2026-11-23T04:59:59.999999998+01:00", type = "unstable")
            db.appendCommit(7, 2, Epoch)
            db.appendCommit(7, 3, Done)
            db.appendCommit(7, 5, "2026-11-23T05:00:00.000000001+01:00", type = "settings")
            db.appendCommit(8, 4, "2026-11-23T04:00:00Z")
            db.appendCommit(7, 6, "2026-11-23T04:00:00Z")
            val original = oldResponse(fields = ""","diagnostics":{"response_id":"preserve"},"latency_ms":7,"retries":2,"opaque":[1]""")
            db.appendCommit(7, 5, Done, Response, original)
            db.appendCommit(9, 1, Before, type = "llm_request_meta_legacy", payload = """{ "total_tokens": 614 }""")
            val keys = db.query("""SELECT agent_id,type,"index",created_at FROM agent_commits ORDER BY 1,2,3""")

            db.transaction { backfillRecentHomeResponseTps(db) }

            val actual = db.responsePayload(7, 5)
            val originalObject = Json.parseToJsonElement(original) as JsonObject
            val result = Json.parseToJsonElement(actual) as JsonObject
            assertEquals(originalObject, JsonObject(result - "elapsed_ns" - "output_tokens"))
            val typed = AgentCommitType.LlmRequestMetaResponse.decodePayloadJson(actual) as LlmRequestMeta.Response
            assertEquals(3L, typed.elapsedNanoseconds)
            assertEquals(0L, typed.outputTokens)
            assertEquals(42L, typed.totalTokens)
            assertEquals(0L, typed.usage?.outputTokens)
            assertEquals(keys, db.query("""SELECT agent_id,type,"index",created_at FROM agent_commits ORDER BY 1,2,3"""))
            assertEquals(1L, db.responseWrites())
            db.transaction { backfillRecentHomeResponseTps(db) }
            assertEquals(actual, db.responsePayload(7, 5))
            assertEquals(1L, db.responseWrites())
        }
    }

    test("accept complete verified values without rewriting and retain exact Long nanoseconds at the boundary") {
        openInMemorySqliteConnection().use { db ->
            db.installResponseFixture()
            db.appendCommit(1, 1, Before)
            val already = """ { "type":"$Response","total_tokens":42,"usage":{"input_tokens":41,"output_tokens":1,"total_tokens":42},"elapsed_ns":3,"output_tokens":1 } """
            db.appendCommit(1, 2, Done, Response, already)
            val start = Instant.parse("2000-01-01T00:00:00Z")
            val end = Instant.fromEpochSeconds(start.epochSeconds + 9_223_372_036L, 854_775_807)
            db.appendCommit(2, 1, start.toString())
            db.appendCommit(2, 2, end.toString(), Response, oldResponse())

            db.transaction { backfillRecentHomeResponseTps(db) }

            assertEquals(already, db.responsePayload(1, 2))
            val typed = AgentCommitType.LlmRequestMetaResponse.decodePayloadJson(db.responsePayload(2, 2)) as LlmRequestMeta.Response
            assertEquals(Long.MAX_VALUE, typed.elapsedNanoseconds)
            assertEquals(1L, db.responseWrites())
        }
    }

    test("reject missing or ambiguous timing and usage with a safe response locator") {
        val cases = listOf(
            RejectedResponse(payload = """{"type":"$Response","total_tokens":42,"opaque":"private-text"}""", reason = "usage object"),
            RejectedResponse(payload = oldResponse(tokens = "null"), reason = "usage.output_tokens"),
            RejectedResponse(payload = oldResponse(tokens = "\"0\""), reason = "usage.output_tokens"),
            RejectedResponse(payload = oldResponse(tokens = "1e0"), reason = "usage.output_tokens"),
            RejectedResponse(payload = oldResponse(tokens = "-1"), reason = "usage.output_tokens"),
            RejectedResponse(payload = oldResponse(tokens = "9223372036854775808"), reason = "usage.output_tokens"),
            RejectedResponse(payload = oldResponse(fields = ""","elapsed_ns":3"""), reason = "partial TPS fields"),
            RejectedResponse(payload = oldResponse(fields = ""","output_tokens":0"""), reason = "partial TPS fields"),
            RejectedResponse(payload = oldResponse(fields = ""","elapsed_ns":0,"output_tokens":0"""), reason = "elapsed_ns must be"),
            RejectedResponse(payload = oldResponse(fields = ""","elapsed_ns":2,"output_tokens":0"""), reason = "does not match the same-Agent predecessor"),
            RejectedResponse(payload = oldResponse(fields = ""","elapsed_ns":3,"output_tokens":12"""), reason = "does not match usage.output_tokens"),
            RejectedResponse(payload = oldResponse(fields = ""","opaque":"private-text","opaque":1"""), reason = "duplicate JSON member"),
            RejectedResponse(predecessor = null, reason = "no earlier same-Agent"),
            RejectedResponse(predecessor = Epoch, reason = "no earlier same-Agent"),
            RejectedResponse(predecessor = Done, reason = "no earlier same-Agent"),
            RejectedResponse(predecessor = "private-text", reason = "invalid timestamp at predecessor index=1"),
            RejectedResponse(predecessor = "2026-11-23T04:00:00.000000002Z", reason = "later than the response"),
            RejectedResponse(predecessor = "1700-01-01T00:00:00Z", reason = "overflow Long"),
            RejectedResponse(otherPredecessor = Done, reason = "conflicting timestamps at predecessor index=1"),
            RejectedResponse(otherCompletion = Before, reason = "conflicting timestamps at response index"),
            RejectedResponse(completion = "private-text", reason = "invalid response timestamp"),
            RejectedResponse(completion = Epoch, reason = "legacy epoch sentinel"),
        )
        for (case in cases) {
            openInMemorySqliteConnection().use { db ->
                db.installResponseFixture()
                case.predecessor?.let { db.appendCommit(5, 1, it) }
                case.otherPredecessor?.let { db.appendCommit(5, 1, it, type = "user_message") }
                case.otherCompletion?.let { db.appendCommit(5, 4, it, type = "settings") }
                db.appendCommit(6, 3, Before)
                db.appendCommit(5, 5, Before)
                db.appendCommit(5, 4, case.completion, Response, case.payload)
                val message = assertFailsWith<IllegalStateException> {
                    db.transaction { backfillRecentHomeResponseTps(db) }
                }.message.orEmpty()
                assertContains(message, "agent_id=5, index=4")
                assertContains(message, case.reason)
                assertFalse("private-text" in message)
                assertEquals(case.payload, db.responsePayload(5, 4))
                assertEquals(0L, db.responseWrites())
            }
        }
    }

    test("a response failure after multiple pages rolls back all changes in the caller transaction") {
        openInMemorySqliteConnection().use { db ->
            db.installResponseFixture()
            val original = oldResponse()
            repeat(65) { i ->
                db.appendCommit(1, i * 2, Before)
                db.appendCommit(1, i * 2 + 1, Done, Response, original)
            }
            db.appendCommit(2, 0, Before)
            db.appendCommit(2, 1, Done, Response, oldResponse(tokens = "-1"))

            val message = assertFailsWith<IllegalStateException> {
                db.transaction { backfillRecentHomeResponseTps(db) }
            }.message.orEmpty()
            assertContains(message, "agent_id=2, index=1")
            assertEquals(0L, db.responseWrites())
            assertEquals(original, db.responsePayload(1, 1))
            assertEquals(original, db.responsePayload(1, 129))
        }
    }
}

private data class RejectedResponse(
    val predecessor: String? = Before,
    val otherPredecessor: String? = null,
    val completion: String = Done,
    val otherCompletion: String? = null,
    val payload: String = oldResponse(fields = ",\"opaque\":\"private-text\""),
    val reason: String,
)

private fun oldResponse(tokens: String = "0", fields: String = ""): String =
    """{"type":"$Response","total_tokens":42,"usage":{"input_tokens":42,"output_tokens":$tokens,"total_tokens":42}$fields}"""

private fun SqliteConnection.installResponseFixture() {
    execute(
        """CREATE TABLE agent_commits(
            agent_id INTEGER NOT NULL, type TEXT NOT NULL, "index" INTEGER NOT NULL,
            payload_json TEXT NOT NULL CHECK(json_valid(payload_json)), created_at TEXT NOT NULL,
            PRIMARY KEY (agent_id, type, "index")
        ) STRICT, WITHOUT ROWID""",
    )
    execute("""CREATE INDEX commits_global_index ON agent_commits(agent_id, "index", type)""")
    execute("CREATE TABLE response_writes(written INTEGER)")
    execute(
        """CREATE TRIGGER response_update AFTER UPDATE ON agent_commits
           BEGIN INSERT INTO response_writes VALUES (1); END""",
    )
}

private fun SqliteConnection.appendCommit(agent: Int, index: Int, at: String, type: String = "settings", payload: String = "{}") {
    execute("INSERT INTO agent_commits VALUES (?, ?, ?, ?, ?)", listOf(agent.toLong(), type, index.toLong(), payload, at))
}

private fun SqliteConnection.responsePayload(agent: Int, index: Int): String =
    query(
        """SELECT payload_json FROM agent_commits WHERE agent_id = ? AND type = ? AND "index" = ?""",
        listOf(agent.toLong(), Response, index.toLong()),
    ).single().single() as String

private fun SqliteConnection.responseWrites(): Long =
    query("SELECT count(*) FROM response_writes").single().single() as Long
