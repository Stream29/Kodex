package io.github.stream29.kodex.app.migration.v0_7_5

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal suspend fun migrateToV0_7_5(home: Path, fileSystem: CoroutineFileSystem): Unit = withContext(Dispatchers.IO) {
    val database = Path(home, "kodex.db")
    if (!fileSystem.exists(database)) throw IOException("SQLite database is missing for migration to 0.7.5.")
    openSqliteConnection(database).use { db ->
        db.execute("PRAGMA synchronous=FULL")
        var after: List<Any?> = listOf(-1L, "", -1L)
        while (true) {
            val page = db.query(
                """SELECT c.agent_id, c.type, c."index", c.payload_json, a.session_id, a.parent_agent_id
                    FROM agent_commits c JOIN agents a ON a.agent_id = c.agent_id
                    WHERE (c.agent_id, c.type, c."index") > (?, ?, ?)
                    AND (c.type = 'event_terminal_exited' OR
                         (c.type = 'steer' AND instr(c.payload_json, '"event_terminal_exited"') > 0))
                    ORDER BY c.agent_id, c.type, c."index" LIMIT 128""",
                after,
            )
            if (page.isEmpty()) break
            val updates = page.mapNotNull { row ->
                val agent = row[0] as Long
                val type = row[1] as String
                val index = row[2] as Long
                val payload = row[3] as String
                val root = Path(home, "sessions", (row[4] as Long).toString())
                val directory = if (row[5] == null) root else Path(root, "agents", agent.toString())
                val steer = type == "steer"
                // The mirror may lag or be absent. Convert an existing record in place, without
                // installing missing files or touching pointers. Run before the SQLite page commits
                // so interruption leaves the original rows available for re-entry.
                migrateMirrorName(Path(directory, if (steer) "steer" else "work"), index, steer, fileSystem)
                withExplicitTerminalNames(payload, steer)?.let { listOf(it, agent, type, index) }
            }
            if (updates.isNotEmpty()) db.transaction {
                for (parameters in updates) db.execute(
                    """UPDATE agent_commits SET payload_json = ? WHERE agent_id = ? AND type = ? AND "index" = ?""",
                    parameters,
                )
            }
            after = page.last().take(3)
        }
    }
}

private suspend fun migrateMirrorName(
    directory: Path,
    index: Long,
    steer: Boolean,
    fileSystem: CoroutineFileSystem,
) {
    val record = Path(directory, "$index.json")
    if (!fileSystem.exists(record)) return
    val temporary = Path(directory, ".kodex-v0.7.5-$index.tmp")
    fileSystem.delete(temporary, mustExist = false)
    val payload = withExplicitTerminalNames(fileSystem.readString(record), steer) ?: return
    try {
        fileSystem.writeString(temporary, payload, mustCreate = true)
        fileSystem.atomicMove(temporary, record)
    } finally {
        withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) }
    }
}

private val TerminalExitedTag = JsonPrimitive("event_terminal_exited")

// Frozen v0.7.4 payloads: a tagged Work object or a bare list of tagged steer events.
// Do not decode the payload with the live commit model, which now requires name.
private fun withExplicitTerminalNames(payload: String, steer: Boolean): String? {
    val original = Json.parseToJsonElement(payload)
    if (!steer) return (original as? JsonObject)?.withExplicitTerminalName()?.toString()
    val events = original as JsonArray
    var changed = false
    val updated = events.map { event ->
        ((event as? JsonObject)?.withExplicitTerminalName()?.also { changed = true }) ?: event
    }
    return if (changed) JsonArray(updated).toString() else null
}

private fun JsonObject.withExplicitTerminalName(): JsonObject? {
    if (this["type"] != TerminalExitedTag) return null
    val name = this["name"]
    if (name != null && name != JsonNull && !(name is JsonPrimitive && name.isString && name.content.isBlank())) {
        return null
    }
    if (name == JsonPrimitive("")) return null
    return JsonObject(this + ("name" to JsonPrimitive("")))
}
