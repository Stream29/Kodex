package io.github.stream29.kodex.app.migration.v0_7_2

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_7_0.migrateToV0_7_0
import io.github.stream29.kodex.app.migration.v0_7_1.migrateToV0_7_1
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val resourceFs = SystemCoroutineFileSystem
private const val ResourceTime = "2026-10-25T12:00:00Z"
private const val SourceVersion = "\"0.7.1\""
private const val TargetVersion = "\"0.7.2\""
private const val UnchangedRecord = """{"type":"user_message","content":[{"type":"input_text","text":"untouched"}]}"""

val migrationMirrorResourceTest by testSuite {
    test("large transformed payloads write only affected mirrors") {
        withResourceHome(agents = 32, payloadSize = 512 * 1024) { home ->
            var recordsWritten = 0
            var bytesWritten = 0L
            val measuring = object : CoroutineFileSystem by resourceFs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    assertFalse(path.name == "3.json", "Migration must not rewrite unchanged user mirrors.")
                    if (path.name == "1.json" || path.name == "2.json") {
                        recordsWritten++
                        bytesWritten += content.encodeToByteArray().size
                    }
                    resourceFs.writeString(path, content, append, mustCreate)
                }
            }
            upgradeResourceHome(home, measuring)
            assertEquals(64, recordsWritten)
            assertTrue(bytesWritten >= 32L * 1024 * 1024)
            assertEquals(TargetVersion, resourceFs.readString(Path(home, "version.json")))
            assertResourceMirrors(home, agents = 32)
            println("Migration fixture wrote $recordsWritten changed mirror records ($bytesWritten bytes).")
        }
    }

    test("mirror interruption after database commit leaves an upgradeable Home") {
        withResourceHome(agents = 2, payloadSize = 8 * 1024) { home ->
            val first = Path(home, "sessions", "0", "work", "1.json")
            val last = Path(home, "sessions", "1", "work", "2.json")
            var passedFirst = false
            val failing = object : CoroutineFileSystem by resourceFs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == last) throw IOException("injected changed mirror write failure")
                    resourceFs.writeString(path, content, append, mustCreate)
                    if (path == first) passedFirst = true
                }
            }
            assertFailsWith<IOException> { upgradeResourceHome(home, failing) }
            assertTrue(passedFirst)
            assertFalse(resourceFs.exists(last))
            assertEquals(SourceVersion, resourceFs.readString(Path(home, "version.json")))
            openSqliteConnection(Path(home, "kodex.db"), readOnly = true).use { db ->
                assertEquals(listOf(listOf("ok")), db.query("PRAGMA integrity_check"))
                assertEquals(
                    listOf(listOf(4L)),
                    db.query("SELECT count(*) FROM agent_commits WHERE type = 'read_media_tool_event'"),
                )
            }

            upgradeResourceHome(home, resourceFs)
            assertEquals(TargetVersion, resourceFs.readString(Path(home, "version.json")))
            assertResourceMirrors(home, agents = 2)
        }
    }
}

private suspend fun CoroutineScope.upgradeResourceHome(home: Path, fileSystem: CoroutineFileSystem) {
    prepareKodexHome(home, MigrationVersion("0.7.2"), KodexHomeMigrations, fileSystem).closeAndJoin()
}

private suspend fun withResourceHome(agents: Int, payloadSize: Int, block: suspend (Path) -> Unit) {
    val home = Path(SystemTemporaryDirectory, "kodex-072-mirror-${Random.nextLong()}")
    resourceFs.createDirectories(home, mustCreate = true)
    try {
        migrateToV0_7_0(home, resourceFs)
        migrateToV0_7_1(home, resourceFs)
        val image = "A".repeat(payloadSize)
        openSqliteConnection(Path(home, "kodex.db")).use { db ->
            db.transaction {
                for (agent in 0 until agents) {
                    db.execute("INSERT INTO sessions (session_id, archived) VALUES (?, 0)", listOf(agent.toLong()))
                    db.execute("INSERT INTO agents (agent_id, session_id) VALUES (?, ?)", listOf(agent.toLong(), agent.toLong()))
                    for (index in 1..2) {
                        val value =
                            """{"type":"image_view_tool_event","call_id":"$agent-$index","arguments":{"path":"a.png","detail":"original","environment_id":"local"},"result":{"type":"success","output":{"image_url":"data:image/png;base64,$image","detail":"original"}}}"""
                        db.execute(
                            "INSERT INTO agent_commits VALUES (?, 'image_view_tool_event', ?, ?, ?)",
                            listOf(agent.toLong(), index.toLong(), value, ResourceTime),
                        )
                    }
                    db.execute(
                        "INSERT INTO agent_commits VALUES (?, 'user_message', 3, ?, ?)",
                        listOf(agent.toLong(), UnchangedRecord, ResourceTime),
                    )
                }
            }
        }
        for (agent in 0 until agents) {
            val indexDirectory = Path(home, "sessions", agent.toString(), "index")
            resourceFs.createDirectories(indexDirectory)
            resourceFs.writeString(Path(indexDirectory, "3.json"), UnchangedRecord)
            resourceFs.writeString(Path(indexDirectory, "latest.json"), "3")
        }
        resourceFs.writeString(Path(home, "version.json"), SourceVersion)
        block(home)
    } finally {
        withContext(NonCancellable) { deleteResourceTree(home) }
    }
}

private suspend fun assertResourceMirrors(home: Path, agents: Int) {
    openSqliteConnection(Path(home, "kodex.db"), readOnly = true).use { db ->
        for (agent in 0 until agents) {
            val dir = Path(home, "sessions", agent.toString())
            assertEquals(UnchangedRecord, resourceFs.readString(Path(dir, "index", "3.json")))
            assertEquals("2", resourceFs.readString(Path(dir, "work", "latest.json")))
            for (index in 1..2) {
                val payload = db.query(
                    """SELECT payload_json FROM agent_commits WHERE agent_id = ? AND type = 'read_media_tool_event' AND "index" = ?""",
                    listOf(agent.toLong(), index.toLong()),
                ).single().single()
                assertEquals(payload, resourceFs.readString(Path(dir, "work", "$index.json")))
            }
        }
    }
}

private suspend fun deleteResourceTree(path: Path) {
    val metadata = resourceFs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) resourceFs.list(path).forEach { deleteResourceTree(it) }
    resourceFs.delete(path, mustExist = false)
}
