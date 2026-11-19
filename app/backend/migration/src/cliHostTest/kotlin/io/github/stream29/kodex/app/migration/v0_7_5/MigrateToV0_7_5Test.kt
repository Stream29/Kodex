package io.github.stream29.kodex.app.migration.v0_7_5

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_7_2.migrateToV0_7_2
import io.github.stream29.kodex.app.migration.v0_7_3.migrateToV0_7_3
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

private val fs = SystemCoroutineFileSystem
private val target = MigrationVersion("0.7.5")

// Frozen v0.7.4 on-disk fixtures, including both ways the nullable name was written.
private const val Missing = """{"type":"event_terminal_exited","session_id":37,"command":"first\nlast","exit_code":23}"""
private const val NullName = """{"type":"event_terminal_exited","session_id":38,"name":null,"command":"second","exit_code":0}"""
private const val Blank = """{"type":"event_terminal_exited","session_id":39,"name":" \t","command":"third","exit_code":0}"""
private const val Named = """{ "type":"event_terminal_exited", "session_id":40, "name":"检查测试", "command":"fourth", "exit_code":0 }"""
private const val UserEvent = """{"type":"user_message","content":[{"type":"input_text","text":"Keep me"}]}"""
private const val Timestamp = "2026-11-19T00:00:00Z"

val migrateToV0_7_5Test by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "terminal-exit-migration-${Random.nextLong().toULong()}").also {
            fs.createDirectories(it, mustCreate = true)
        }
    } closeWith { deleteTree(this) } asParameterForEach {
        test("upgrades a 0.7.2 Home through 0.7.3, preserves named bytes and converts bounded pages") { home ->
            migrateToV0_7_2(home, fs)
            val checkpoint = Path(home, "version.json")
            val path = Path(home, "kodex.db")
            fs.writeString(checkpoint, "\"0.7.2\"")
            openSqliteConnection(path).use { db ->
                db.execute("INSERT INTO sessions VALUES(12, 0)")
                db.execute("INSERT INTO agents VALUES(7, 12, NULL), (42, 12, 7)")
                db.insertCommit(7, "event_terminal_exited", 0, Missing)
                db.insertCommit(7, "event_terminal_exited", 1, Named)
                db.insertCommit(7, "event_terminal_exited", 2, Blank)
                db.insertCommit(7, "user_message", 4, UserEvent)
                db.insertCommit(42, "steer", 3, "[$UserEvent,$NullName,$Named]")
                db.transaction {
                    for (index in 5L..140L) db.insertCommit(42, "event_terminal_exited", index, NullName)
                }
            }
            val work = Path(home, "sessions", "12", "work")
            val steer = Path(home, "sessions", "12", "agents", "42", "steer")
            fs.createDirectories(work)
            fs.createDirectories(steer)
            fs.writeString(Path(work, "0.json"), Missing)
            fs.writeString(Path(work, "1.json"), Named)
            fs.writeString(Path(work, "latest.json"), "2")
            fs.writeString(Path(steer, "3.json"), "[$UserEvent,$NullName,$Named]")

            prepareKodexHome(home, target, KodexHomeMigrations, fs).closeAndJoin()
            assertEquals("\"0.7.5\"", fs.readString(checkpoint))
            openSqliteConnection(path).use { db ->
                assertEquals(StableTerminalExitedEvent(37, "", "first\nlast", 23), db.payload(7, "event_terminal_exited", 0).terminal())
                assertEquals(Named, db.payload(7, "event_terminal_exited", 1))
                assertEquals("", db.payload(7, "event_terminal_exited", 2).terminal().name)
                assertEquals(UserEvent, db.payload(7, "user_message", 4))
                val queued = assertIs<SteerSnapshot>(AgentCommitType.Steer.decodePayloadJson(db.payload(42, "steer", 3)))
                assertEquals(listOf("", "检查测试"), queued.events.filterIsInstance<StableTerminalExitedEvent>().map { it.name })
                assertEquals(3, queued.events.size)
                assertEquals(db.payload(42, "steer", 3), fs.readString(Path(steer, "3.json")))
                assertEquals(db.payload(7, "event_terminal_exited", 0), fs.readString(Path(work, "0.json")))
                assertEquals("", db.payload(42, "event_terminal_exited", 140).terminal().name)
                assertEquals(listOf(listOf(Timestamp)), db.query("SELECT DISTINCT created_at FROM agent_commits"))
            }
            assertEquals(Named, fs.readString(Path(work, "1.json")))
            assertEquals("2", fs.readString(Path(work, "latest.json")))
            assertFalse(fs.exists(Path(work, "2.json")))
        }

        test("retries after an interrupted mirror rename and a post-commit checkpoint failure") { home ->
            migrateToV0_7_2(home, fs)
            migrateToV0_7_3(home, fs)
            val checkpoint = Path(home, "version.json")
            val path = Path(home, "kodex.db")
            fs.writeString(checkpoint, "\"0.7.3\"")
            val work = Path(home, "sessions", "12", "work")
            val mirror = Path(work, "0.json")
            fs.createDirectories(work)
            fs.writeString(mirror, Missing)
            fs.writeString(Path(work, ".kodex-v0.7.5-0.tmp"), "left by interrupted writer")
            openSqliteConnection(path).use { db ->
                db.execute("INSERT INTO sessions VALUES(12, 0)")
                db.execute("INSERT INTO agents (agent_id, session_id, parent_agent_id) VALUES(7, 12, NULL)")
                db.insertCommit(7, "event_terminal_exited", 0, Missing)
            }
            val failedRename = object : CoroutineFileSystem by fs {
                override suspend fun atomicMove(source: Path, destination: Path) {
                    if (destination == mirror) throw IOException("Interrupted before mirror rename")
                    fs.atomicMove(source, destination)
                }
            }
            assertFailsWith<IOException> { prepareKodexHome(home, target, KodexHomeMigrations, failedRename) }
            assertEquals("\"0.7.3\"", fs.readString(checkpoint))
            assertEquals(Missing, fs.readString(mirror))
            assertEquals(Missing, openSqliteConnection(path).use { it.payload(7, "event_terminal_exited", 0) })
            assertFalse(fs.exists(Path(work, ".kodex-v0.7.5-0.tmp")))

            val failedCheckpoint = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == checkpoint) throw IOException("Interrupted after DB commit")
                    fs.writeString(path, content, append, mustCreate)
                }
            }
            assertFailsWith<IOException> { prepareKodexHome(home, target, KodexHomeMigrations, failedCheckpoint) }
            assertEquals("\"0.7.3\"", fs.readString(checkpoint))
            val committed = openSqliteConnection(path).use { it.payload(7, "event_terminal_exited", 0) }
            assertEquals("", committed.terminal().name)
            assertEquals(committed, fs.readString(mirror))
            prepareKodexHome(home, target, KodexHomeMigrations, fs).closeAndJoin()
            assertEquals("\"0.7.5\"", fs.readString(checkpoint))
            assertEquals(committed, openSqliteConnection(path).use { it.payload(7, "event_terminal_exited", 0) })
            assertEquals(committed, fs.readString(mirror))
        }
    }
}

private fun SqliteConnection.insertCommit(agent: Long, type: String, index: Long, payload: String) =
    execute("INSERT INTO agent_commits VALUES(?,?,?,?,?)", listOf(agent, type, index, payload, Timestamp))

private fun SqliteConnection.payload(agent: Long, type: String, index: Long): String =
    query("""SELECT payload_json FROM agent_commits WHERE agent_id = ? AND type = ? AND "index" = ?""", listOf(agent, type, index))
        .single().single() as String

private fun String.terminal(): StableTerminalExitedEvent =
    assertIs(AgentCommitType.EventTerminalExited.decodePayloadJson(this))

private suspend fun deleteTree(path: Path) {
    if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach { deleteTree(it) }
    fs.delete(path, mustExist = false)
}
