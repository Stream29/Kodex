package io.github.stream29.kodex.app.migration.v0_7_3

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_7_0.migrateToV0_7_0
import io.github.stream29.kodex.app.migration.v0_7_1.migrateToV0_7_1
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val probeFs = SystemCoroutineFileSystem
private val frozenTarget = MigrationVersion("0.7.2")
private const val FixtureStamp = "2026-11-18T00:00:00Z"

val frozenV072RetentionProbeTest by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-v072-retention-${Random.nextLong().toULong()}").also {
            probeFs.createDirectories(it, mustCreate = true)
        }
    } closeWith { removeFixtureTree(this) } asParameterForEach {
        test("frozen retention keeps the four old types and identical mirror after interruption") { sandbox ->
            val clean = Path(sandbox, "clean")
            val retried = Path(sandbox, "retry")
            seedLegacyRetention(clean)
            seedLegacyRetention(retried)
            prepareKodexHome(clean, frozenTarget, KodexHomeMigrations, probeFs).closeAndJoin()
            val expected = retentionProbeSnapshot(clean)
            val failingPath = Path(retried, "sessions", "7", "index", "4.json")
            val failMirror = object : CoroutineFileSystem by probeFs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == failingPath) throw IOException("Injected retention mirror interruption")
                    probeFs.writeString(path, content, append, mustCreate)
                }
            }
            assertFailsWith<IOException> { prepareKodexHome(retried, frozenTarget, KodexHomeMigrations, failMirror) }
            assertEquals("\"0.7.1\"", probeFs.readString(Path(retried, "version.json")))
            prepareKodexHome(retried, frozenTarget, KodexHomeMigrations, probeFs).closeAndJoin()
            assertEquals(expected, retentionProbeSnapshot(retried))
            prepareKodexHome(retried, frozenTarget, KodexHomeMigrations, probeFs).closeAndJoin()
            assertEquals(expected, retentionProbeSnapshot(retried))
            println("FROZEN_V072_RETENTION_PROBE $expected")
        }
    }
}

private suspend fun seedLegacyRetention(home: Path) {
    probeFs.createDirectories(home)
    migrateToV0_7_0(home, probeFs)
    migrateToV0_7_1(home, probeFs)
    probeFs.writeString(Path(home, "version.json"), "\"0.7.1\"")
    val records = listOf(
        "user_message" to """{"type":"user_message","content":[{"type":"input_text","text":"one"}]}""",
        "event_ask_user" to """{"type":"event_ask_user","ask_id":1,"arguments":{"questions":[]},"response":{"answers":{}}}""",
        "event_schedule" to """{"type":"event_schedule","schedule_id":1,"description":"scheduled"}""",
        "plan_update" to """{"type":"plan_update","call_id":"plan","arguments":{"plan":[]}}""",
        "context_compaction" to """{"type":"context_compaction","encrypted_content":"first"}""",
        "user_message" to """{"type":"user_message","content":[{"type":"input_text","text":"two"}]}""",
        "context_compaction" to """{"type":"context_compaction","encrypted_content":"explicit","retained_items":[{"type":"event_schedule","schedule_id":1,"description":"scheduled"}]}""",
        "context_compaction" to """{"type":"context_compaction","encrypted_content":"next"}""",
    )
    openSqliteConnection(Path(home, "kodex.db")).use { db ->
        db.execute("INSERT INTO sessions(session_id) VALUES(7)")
        db.execute("INSERT INTO agents(agent_id, session_id) VALUES(7, 7)")
        // Transitional test data includes the ask and schedule shapes accepted by 0.7.2.
        db.execute("PRAGMA ignore_check_constraints = ON")
        db.transaction {
            records.forEachIndexed { index, (type, payload) ->
                db.execute("INSERT INTO agent_commits VALUES(7, ?, ?, ?, ?)", listOf(type, index.toLong(), payload, FixtureStamp))
            }
        }
    }
    val mirror = Path(home, "sessions", "7", "index")
    probeFs.createDirectories(mirror)
    records.forEachIndexed { index, (_, payload) -> probeFs.writeString(Path(mirror, "$index.json"), payload) }
    probeFs.writeString(Path(mirror, "latest.json"), "7")
}

private suspend fun retentionProbeSnapshot(home: Path): String {
    assertEquals("\"0.7.2\"", probeFs.readString(Path(home, "version.json")))
    val rows = openSqliteConnection(Path(home, "kodex.db")).use { db ->
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        db.query("""SELECT "index", type, payload_json, created_at FROM agent_commits ORDER BY "index", type""")
    }
    val mirror = Path(home, "sessions", "7", "index")
    val files = listOf(4, 6, 7).map { i ->
        val text = probeFs.readString(Path(mirror, "$i.json"))
        val row = rows.single { it[0] == i.toLong() }
        assertEquals(row[2], text)
        val types = Json.parseToJsonElement(text).jsonObject.getValue("retained_items").jsonArray
            .map { it.jsonObject.getValue("type").jsonPrimitive.content }
        assertEquals(
            if (i == 4) listOf("user_message", "event_ask_user", "plan_update") else listOf("event_schedule"),
            types,
        )
        text
    }
    return "$rows|$files|${probeFs.readString(Path(mirror, "latest.json"))}"
}

private suspend fun removeFixtureTree(path: Path) {
    val metadata = probeFs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) probeFs.list(path).forEach { removeFixtureTree(it) }
    probeFs.delete(path, mustExist = false)
}
