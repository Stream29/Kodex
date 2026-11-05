package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

public actual fun initializeSqlitePhaseOneSchema(connection: SqliteConnection) {
    connection.transaction {
        SqlitePhaseOneSchema.split(';').filter { it.isNotBlank() }.forEach { connection.execute(it) }
        connection.execute("PRAGMA user_version=1")
    }
}

public actual fun ensureSqlitePhaseOneSchema(connection: SqliteConnection) {
    when (connection.query("PRAGMA user_version").single().single() as Long) {
        0L -> initializeSqlitePhaseOneSchema(connection)
        1L -> {
            for ((name, type) in listOf("sessions" to "table", "agents" to "table", "agent_commits" to "table", "session_catalog" to "view")) {
                check(connection.query("SELECT 1 FROM sqlite_master WHERE name = ? AND type = ?", listOf(name, type)).size == 1) {
                    "Incomplete SQLite phase one schema: $name"
                }
            }
        }
        else -> error("Unsupported SQLite schema version")
    }
}
