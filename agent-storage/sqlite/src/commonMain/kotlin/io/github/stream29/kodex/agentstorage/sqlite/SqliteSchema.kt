package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

private val currentDdl = SqliteSchema.split(';').map(String::trim).filter(String::isNotEmpty) +
    listOf(CreateSessionGroupsTable.trim(), CreateSessionGroupsIndex.trim())

// SQLite keeps the CREATE statement text, excluding the final semicolon, in sqlite_schema.
private val currentSchema = (currentDdl + "CREATE TABLE sqlite_sequence(name,seq)").toSet()

public actual fun initializeKodexHomeDatabase(connection: SqliteConnection) {
    connection.transaction {
        check(connection.query("PRAGMA main.application_id") == listOf(listOf(0L)) &&
            connection.query("PRAGMA main.user_version") == listOf(listOf(0L)) &&
            connection.query("SELECT 1 FROM main.sqlite_schema LIMIT 1").isEmpty()
        ) { "Cannot initialize SQLite: the database is not fresh." }
        currentDdl.forEach { connection.execute(it) }
        connection.execute("PRAGMA main.user_version=$SqliteSchemaVersion")
        connection.execute("PRAGMA main.application_id=$KodexHomeDatabaseApplicationId")
    }
}

public actual fun requireKodexHomeDatabase(connection: SqliteConnection) {
    check(connection.query("PRAGMA main.application_id") == listOf(listOf(KodexHomeDatabaseApplicationId.toLong()))) {
        "Unrecognized Kodex SQLite database signature."
    }
    check(connection.query("PRAGMA main.user_version") == listOf(listOf(SqliteSchemaVersion.toLong()))) {
        "Unsupported Kodex SQLite schema version."
    }
    check(
        connection.query("SELECT sql FROM main.sqlite_schema WHERE sql IS NOT NULL").map { it.single() }.toSet() == currentSchema,
    ) { "Incomplete or unexpected Kodex SQLite schema." }
}

public actual fun requireEmptyKodexHomeDatabase(connection: SqliteConnection) {
    requireKodexHomeDatabase(connection)
    for ((rawName) in connection.query("SELECT name FROM main.sqlite_schema WHERE type = 'table'")) {
        val name = (rawName as String).replace("\"", "\"\"")
        check(connection.query("SELECT 1 FROM main.\"$name\" LIMIT 1").isEmpty()) {
            "Interrupted Kodex database initialization contains data in $name."
        }
    }
}
