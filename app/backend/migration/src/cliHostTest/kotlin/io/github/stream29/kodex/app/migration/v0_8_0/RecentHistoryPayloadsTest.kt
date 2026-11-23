package io.github.stream29.kodex.app.migration.v0_8_0

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

public val recentHistoryPayloadsTest by testSuite {
    test("both recent name shapes and all historical settings become readable without touching other rows") {
        openInMemorySqliteConnection().use { db ->
            db.installHistoryFixture()
            val unchanged = listOf(
                "settings" to """{ "model": "test", "name": "already", "future": 4 }""",
                "event_terminal_exited" to """{ "type": "event_terminal_exited", "session_id": 10, "name": " 保留 😀 ", "command": "private", "exit_code": 0 }""",
                "command_execution_tool_event" to """{ "type": "command_execution_tool_event", "action": {"type":"write_stdin","arguments":{"name":null}} }""",
                "unstable" to """[ { "type": "invalid_tool_call", "name": null } ]""",
                "ask_user" to """[ { "unrelated": 1 } ]""",
                "llm_request_meta_response" to """{ "still": "outside this pass" }""",
            )
            unchanged.forEachIndexed { index, (type, json) -> db.appendHistory(0, type, index, json) }
            db.appendHistory(1, "settings", 0, """{"model":"test","threadName":"first","cwd":"/test","future":{"key":1}}""")
            db.appendHistory(1, "settings", 7, """{"model":"test","threadName":"","name":""}""")
            db.appendHistory(2, "settings", 0, """{"model":"test","threadName":"child"}""")
            db.appendHistory(1, "event_terminal_exited", 9, """{"type":"event_terminal_exited","session_id":10,"command":"private","exit_code":0}""")
            db.appendHistory(1, "command_execution_tool_event", 9, """{"type":"command_execution_tool_event","call_id":"c","action":{"type":"exec_command","arguments":{"cmd":"private","name":null,"yield_time_ms":1,"extra":3}},"result":{"type":"failure","message":"private output"}}""")
            db.appendHistory(1, "await_event_tool_event", 10, """{"type":"await_event_tool_event","call_id":"a","arguments":{"terminals":[7,2,7],"extra":"keep"},"result":{"type":"output","value":{"timestamp":"2026-11-23T00:00:00Z"}}}""")
            db.appendHistory(1, "await_event_tool_event", 11, """{"type":"await_event_tool_event","call_id":"b","arguments":{},"result":{"type":"failure","message":"keep"}}""")
            db.appendHistory(1, "await_event_tool_event", 13, """{"type":"await_event_tool_event","call_id":"d","arguments":{"terminals":[]},"result":{"type":"failure","message":"keep"}}""")
            db.appendHistory(1, "unstable", 12, """[{"type":"command_execution","call_id":"p","action":{"type":"exec_command","arguments":{"cmd":"private","name":"  检查\n状态\u2028😀  ","yield_time_ms":1}}},{"type":"await_event","call_id":"w","arguments":{"events":[{"type":"timeout","duration_ms":50}],"name":0},"timeout_at":"2026-11-23T00:00:00.05Z","armed_for_agent_id":1}]""")
            db.appendHistory(1, "steer", 12, """[{"type":"event_terminal_exited","session_id":2,"name":"","command":"private","exit_code":0},{"type":"event_terminal_exited","session_id":3,"name":" \u0085 ","command":"private","exit_code":0}]""")
            val originalKeys = db.query("""SELECT agent_id,type,"index",created_at FROM agent_commits ORDER BY 1,2,3""")

            db.transaction { normalizeRecentHomeHistory(db) }

            assertEquals(originalKeys, db.query("""SELECT agent_id,type,"index",created_at FROM agent_commits ORDER BY 1,2,3"""))
            unchanged.forEachIndexed { index, (type, json) -> assertEquals(json, db.history(0, type, index)) }
            assertPayload("""{"model":"test","name":"first","cwd":"/test","future":{"key":1}}""", db.history(1, "settings", 0))
            assertPayload("""{"model":"test","name":""}""", db.history(1, "settings", 7))
            assertPayload("""{"model":"test","name":"child"}""", db.history(2, "settings", 0))
            assertPayload("""{"type":"event_terminal_exited","session_id":10,"name":"Terminal #10","command":"private","exit_code":0}""", db.history(1, "event_terminal_exited", 9))
            assertPayload("""{"type":"command_execution_tool_event","call_id":"c","action":{"type":"exec_command","arguments":{"cmd":"private","name":"Run a command","yield_time_ms":1,"extra":3}},"result":{"type":"failure","message":"private output"}}""", db.history(1, "command_execution_tool_event", 9))
            assertPayload(
                """{"type":"await_event_tool_event","call_id":"a","arguments":{"events":[{"type":"terminal_exited","session_id":7},{"type":"terminal_exited","session_id":2},{"type":"terminal_exited","session_id":7}],"name":"Wait for events","extra":"keep"},"result":{"type":"output","value":{"timestamp":"2026-11-23T00:00:00Z"}}}""",
                db.history(1, "await_event_tool_event", 10),
            )
            assertPayload(
                """{"type":"await_event_tool_event","call_id":"b","arguments":{"events":[{"type":"user_message"},{"type":"ask_user_answered"},{"type":"schedule_triggered"}],"name":"Wait for events"},"result":{"type":"failure","message":"keep"}}""",
                db.history(1, "await_event_tool_event", 11),
            )
            assertPayload(
                """{"type":"await_event_tool_event","call_id":"d","arguments":{"events":[{"type":"user_message"},{"type":"ask_user_answered"},{"type":"schedule_triggered"}],"name":"Wait for events"},"result":{"type":"failure","message":"keep"}}""",
                db.history(1, "await_event_tool_event", 13),
            )
            assertPayload(
                """[{"type":"command_execution","call_id":"p","action":{"type":"exec_command","arguments":{"cmd":"private","name":"检查 状态 😀","yield_time_ms":1}}},{"type":"await_event","call_id":"w","arguments":{"events":[{"type":"timeout","duration_ms":50}],"name":"Wait for events"},"timeout_at":"2026-11-23T00:00:00.05Z","armed_for_agent_id":1}]""",
                db.history(1, "unstable", 12),
            )
            assertEquals(
                listOf("Terminal #2", "Terminal #3"),
                (Json.parseToJsonElement(db.history(1, "steer", 12)) as JsonArray).map {
                    ((it as JsonObject).getValue("name") as JsonPrimitive).content
                },
            )
            for ((type, payload) in db.query("SELECT type,payload_json FROM agent_commits WHERE agent_id > 0")) {
                AgentCommitType.entries.single { it.wireName == type }.decodePayloadJson(payload as String)
            }
            assertEquals(10L, db.numberOfHistoryWrites())
            db.transaction { normalizeRecentHomeHistory(db) }
            assertEquals(10L, db.numberOfHistoryWrites())
        }
    }

    test("settings, await, array and discriminator conflicts are located without leaking the JSON") {
        val invalidRows = listOf(
            Triple("settings", """{"threadName":"private-text","name":"conflict"}""", "conflicting settings"),
            Triple("settings", """{"threadName":null,"future":"private-text"}""", "settings.threadName"),
            Triple("settings", """{"name":false,"future":"private-text"}""", "settings.name"),
            Triple("settings", """{"threadName":"private-text","future":1,"future":2}""", "Duplicate JSON member"),
            Triple("settings", "{\"threadName\":\"private-text\"", "expected valid JSON"),
            Triple("await_event_tool_event", """{"type":"await_event_tool_event","arguments":{"events":[],"terminals":[]},"opaque":"private-text"}""", "both events and terminals"),
            Triple("await_event_tool_event", """{"type":"await_event_tool_event","arguments":{"events":[]},"opaque":"private-text"}""", "must not be empty"),
            Triple("await_event_tool_event", """{"type":"await_event_tool_event","arguments":{"terminals":["3"]},"opaque":"private-text"}""", "JSON integer"),
            Triple("command_execution_tool_event", """{"type":"command_execution_tool_event","action":{"type":"exec_command"},"opaque":"private-text"}""", "action.arguments"),
            Triple("event_terminal_exited", """{"type":"event_terminal_exited","name":"private-text"}""", "session_id"),
            Triple("event_terminal_exited", """{"type":"private-text"}""", "discriminator"),
            Triple("steer", """{"events":[],"opaque":"private-text"}""", "must be an array"),
            Triple("unstable", """["private-text"]""", "must be an object"),
        )
        openInMemorySqliteConnection().use { db ->
            db.installHistoryFixture()
            for ((type, payload, reason) in invalidRows) {
                db.appendHistory(8, type, 61, payload)
                val message = assertFailsWith<IllegalStateException> {
                    db.transaction { normalizeRecentHomeHistory(db) }
                }.message.orEmpty()
                assertContains(message, "agent_id=8, type=$type, index=61")
                assertContains(message, reason)
                assertFalse("private-text" in message)
                assertEquals(payload, db.history(8, type, 61))
                db.execute("DELETE FROM agent_commits")
            }
            assertEquals(0L, db.numberOfHistoryWrites())
        }
    }

    test("the caller can roll back writes across multiple pages and retry with the original keys") {
        openInMemorySqliteConnection().use { db ->
            db.installHistoryFixture()
            val old = """{ "threadName": "old" }"""
            repeat(69) { db.appendHistory(1, "settings", it * 2, old) }
            db.appendHistory(2, "settings", 0, """{"threadName":"old","name":"different"}""")
            assertFailsWith<IllegalStateException> {
                db.transaction { normalizeRecentHomeHistory(db) }
            }
            assertEquals(0L, db.numberOfHistoryWrites())
            assertEquals(old, db.history(1, "settings", 0))
            assertEquals(old, db.history(1, "settings", 136))
            db.execute("DELETE FROM agent_commits WHERE agent_id = 2")
            db.transaction { normalizeRecentHomeHistory(db) }
            assertEquals(69L, db.numberOfHistoryWrites())
            assertPayload("""{"name":"old"}""", db.history(1, "settings", 136))
        }
    }
}

private fun SqliteConnection.installHistoryFixture() {
    execute(
        """CREATE TABLE agent_commits(
            agent_id INTEGER NOT NULL, type TEXT NOT NULL, "index" INTEGER NOT NULL,
            payload_json TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY (agent_id, type, "index")
        ) STRICT, WITHOUT ROWID""",
    )
    execute("CREATE TABLE history_writes(written INTEGER)")
    execute(
        """CREATE TRIGGER history_update AFTER UPDATE ON agent_commits
           BEGIN INSERT INTO history_writes VALUES (1); END""",
    )
}

private fun SqliteConnection.appendHistory(agent: Int, type: String, index: Int, payload: String) {
    execute(
        "INSERT INTO agent_commits VALUES (?, ?, ?, ?, ?)",
        listOf(agent.toLong(), type, index.toLong(), payload, "2026-11-23T00:00:00Z"),
    )
}

private fun SqliteConnection.history(agent: Int, type: String, index: Int): String =
    query(
        """SELECT payload_json FROM agent_commits WHERE agent_id = ? AND type = ? AND "index" = ?""",
        listOf(agent.toLong(), type, index.toLong()),
    ).single().single() as String

private fun SqliteConnection.numberOfHistoryWrites(): Long =
    query("SELECT count(*) FROM history_writes").single().single() as Long

private fun assertPayload(expected: String, actual: String) =
    assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
