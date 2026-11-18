package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.initializeSqlitePhaseOneSchema
import io.github.stream29.kodex.rpc.models.AgentSqlCellBytes
import io.github.stream29.kodex.rpc.models.AgentSqlError
import io.github.stream29.kodex.rpc.models.AgentSqlQueryResult
import io.github.stream29.kodex.rpc.models.AgentSqlResultBytes
import io.github.stream29.kodex.rpc.models.AgentSqlResultJson
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LongQuery =
    "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n) SELECT sum(x) FROM n"

val backendAgentSqlTest by testSuite(compartment = { TestCompartment.RealTime }) {
    testFixture {
        val root = Path(SystemTemporaryDirectory, "kodex-agent-sql-${Random.nextLong().toULong()}")
        val fs = SystemCoroutineFileSystem
        fs.createDirectories(root, mustCreate = true)
        Path(root, "kodex.db").also { path ->
            openSqliteConnection(path).use { db ->
                initializeSqlitePhaseOneSchema(db)
                db.query("PRAGMA journal_mode=WAL")
                db.execute("INSERT INTO sessions VALUES(12,1)")
                db.execute("INSERT INTO agents VALUES(12,12,NULL)")
                db.execute("""INSERT INTO agent_commits VALUES(12,'settings',0,'{"threadName":"fixture","cwd":"/fixture"}','t0')""")
                db.execute("""INSERT INTO agent_commits VALUES(12,'event_terminal_exited',1,'{"session_id":37,"exit_code":18}','t1')""")
                db.execute("""INSERT INTO agent_commits VALUES(12,'llm_request_meta_response',2,'{"total_tokens":1598,"latency_ms":25}','t2')""")
                db.execute("CREATE TABLE internal_test_only (private_value TEXT)")
            }
        }
    } closeWith {
        val fs = SystemCoroutineFileSystem
        val root = checkNotNull(parent)
        fs.list(root).forEach { fs.delete(it) }
        fs.delete(root)
    } asParameterForEach {
        test("published query model and examples use only allowed relations and scalar functions") { path ->
            val service = BackendAgentSql(path)
            assertEquals(listOf("sessions", "agents", "agent_commits", "session_catalog"), service.schema().relations.map { it.name })
            for (example in service.schema().examples) {
                val result = service.query(example.sql)
                assertNull(result.error, "${example.title}: ${result.error}")
                assertEquals(1, result.rows?.size)
            }
            val reply = service.query("SELECT count(*) AS same, NULL AS same FROM sessions; /* trailing */")
            assertNull(reply.error)
            assertEquals(listOf("same", "same"), reply.columns)
            val row = assertNotNull(reply.rows).single()
            assertEquals("1", row[0].jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals(JsonNull, row[1])
            listOf(
                "SELECT * FROM internal_test_only", "WITH private AS (SELECT * FROM internal_test_only) SELECT * FROM private",
                "SELECT 1; SELECT 2", "SELECT * FROM sqlite_schema", "PRAGMA query_only=OFF",
                "UPDATE sessions SET archived=0", "SELECT * FROM json_each('[]')", "EXPLAIN SELECT * FROM agents",
                "SELECT random()", "SELECT 'a'\u0000",
            ).forEach { query -> assertNotNull(service.query(query).error, query) }
            val unparsed = service.query("SELECT 1 -- " + "α".repeat(33_000))
            assertEquals(AgentSqlError.INVALID_INPUT, unparsed.error?.code)
            val scalar = service.query("SELECT x'00ff' AS b, 1e999 AS r, 9223372036854775807 AS n")
            val values = assertNotNull(scalar.rows).single()
            assertEquals("AP8=", values[0].jsonObject.getValue("base64").jsonPrimitive.content)
            assertEquals("+infinity", values[1].jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals("9223372036854775807", values[2].jsonObject.getValue("value").jsonPrimitive.content)
        }
        test("real worker times out and cancellation cannot leak the single read slot") { path ->
            val service = BackendAgentSql(path)
            coroutineScope {
                val query = async(start = CoroutineStart.UNDISPATCHED) { service.query(LongQuery) }
                assertEquals(AgentSqlError.BUSY, service.query("SELECT 1").error?.code)
                assertTrue(service.schema().examples.isNotEmpty())
                query.cancelAndJoin()
                assertNull(service.query("SELECT 7").error)
            }
            val timeout = service.query(LongQuery)
            assertEquals(AgentSqlError.TIMEOUT, timeout.error?.code)
            assertNull(timeout.rows)
            assertTrue(timeout.elapsedMillis >= 1000)
            openSqliteConnection(path).use { db ->
                db.execute("BEGIN IMMEDIATE")
                db.execute("INSERT INTO sessions VALUES(14,0)")
                assertEquals(1, service.query("SELECT * FROM sessions").rows?.size)
                db.execute("COMMIT")
            }
            assertEquals(2, service.query("SELECT * FROM sessions").rows?.size)
        }
        test("whole-row limits distinguish exact EOF, truncation and oversized cell or row") { path ->
            val service = BackendAgentSql(path)
            for (size in listOf(1000, 1001)) {
                val reply = service.query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<$size) SELECT x FROM n")
                assertEquals(1000, reply.rows?.size)
                assertEquals(size == 1001, reply.truncated)
            }
            openSqliteConnection(path).use { db ->
                db.execute("UPDATE agent_commits SET payload_json=? WHERE type='settings'", listOf("\"${"α".repeat(120_000)}\""))
            }
            val one = service.query("SELECT payload_json FROM agent_commits WHERE type='settings'")
            assertFalse(assertNotNull(one.truncated))
            val many = service.query("SELECT a.payload_json FROM agent_commits a CROSS JOIN agent_commits b CROSS JOIN agent_commits c WHERE a.type='settings'")
            assertTrue(assertNotNull(many.truncated))
            assertEquals(4, many.rows?.size)
            assertTrue(AgentSqlResultJson.encodeToString(AgentSqlQueryResult.serializer(), many).encodeToByteArray().size <= AgentSqlResultBytes)
            val wide = List(5) { "payload_json" }.joinToString(",")
            assertEquals(AgentSqlError.VALUE_TOO_LARGE,
                service.query("SELECT $wide FROM agent_commits WHERE type='settings'").error?.code)
            openSqliteConnection(path).use { db ->
                db.execute("UPDATE agent_commits SET payload_json=? WHERE type='settings'", listOf("\"${"x".repeat(AgentSqlCellBytes)}\""))
            }
            assertEquals(AgentSqlError.VALUE_TOO_LARGE,
                service.query("SELECT payload_json FROM agent_commits WHERE type='settings'").error?.code)
        }
    }
}
