package io.github.stream29.kodex.app.migration.v0_8_1

import io.github.stream29.kodex.app.migration.GenerationOneApplicationId
import io.github.stream29.kodex.app.migration.frozenHomeDdl
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

private const val HomeDatabaseFile = "kodex.db"
private const val StagingDatabaseFile = ".kodex.db.migrating-0.8.1"
private val StagingDatabaseFiles = listOf(
    StagingDatabaseFile,
    "$StagingDatabaseFile-journal",
    "$StagingDatabaseFile-wal",
    "$StagingDatabaseFile-shm",
)
private val SourceSidecarFiles = listOf(
    "$HomeDatabaseFile-journal",
    "$HomeDatabaseFile-wal",
    "$HomeDatabaseFile-shm",
)

private const val FrozenApplicationId = GenerationOneApplicationId
private const val SourceSchemaVersion = 2L
private const val TargetSchemaVersion = 3L
private const val TargetPageSize = 16384L

private val FrozenV2Ddl = frozenHomeDdl(2)
private val FrozenV3Ddl = frozenHomeDdl(3)

private val FrozenV2Schema = (FrozenV2Ddl + "CREATE TABLE sqlite_sequence(name,seq)").toSet()
private val FrozenV3Schema = (FrozenV3Ddl + "CREATE TABLE sqlite_sequence(name,seq)").toSet()

private val CopiedTables = listOf(
    "sessions" to "session_id",
    "agents" to "agent_id, session_id, parent_agent_id, completed",
    "agent_commits" to """agent_id, type, "index", payload_json, created_at""",
    "session_catalog_view" to "root_agent_id, name, cwd, created_at, updated_at",
    "session_groups" to "cwd_key, cwd, group_nonce",
    "schedules" to "schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at",
    "fork_context_recovery" to "agent_id, checkpoint_index, commit_type, phase, starts_new_turn",
)

internal suspend fun migrateToV0_8_1(home: Path, fileSystem: CoroutineFileSystem) {
    cleanupStagingFiles(home, fileSystem)
    val database = Path(home, HomeDatabaseFile)
    val staging = Path(home, StagingDatabaseFile)

    val alreadyMigrated = withContext(Dispatchers.IO) {
        openExistingSqliteConnection(database).use { source ->
            check(source.query("PRAGMA main.application_id") == listOf(listOf(FrozenApplicationId))) {
                "Unrecognized Kodex SQLite database signature during 0.8.1 migration."
            }
            val userVersion = source.query("PRAGMA main.user_version").singleOrNull()?.singleOrNull() as? Long
            val schema = source.schemaStatements()
            when {
                userVersion == TargetSchemaVersion && schema == FrozenV3Schema -> true
                userVersion == SourceSchemaVersion && schema == FrozenV2Schema -> {
                    // Checkpoint any committed WAL frames into kodex.db and remove WAL/SHM sidecars
                    // so staging reads a self-contained source and atomicMove never meets stale WAL files.
                    check(source.query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete"))) {
                        "Failed to switch source database out of WAL mode before 0.8.1 migration."
                    }
                    false
                }
                else -> error(
                    "Unexpected Kodex SQLite database state for 0.8.1 migration (user_version=$userVersion).",
                )
            }
        }
    }
    if (alreadyMigrated) return

    check(SourceSidecarFiles.none { fileSystem.exists(Path(home, it)) }) {
        "Source SQLite sidecars remained after switching to DELETE journal mode before 0.8.1 migration."
    }

    try {
        withContext(Dispatchers.IO) {
            openSqliteConnection(staging).use { target ->
                check(target.query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete"))) {
                    "Staging database for 0.8.1 migration must use DELETE journal mode."
                }
                target.execute("PRAGMA synchronous=FULL")
                target.execute("PRAGMA main.page_size=$TargetPageSize")
                if (target.query("PRAGMA main.page_size") != listOf(listOf(TargetPageSize))) {
                    target.execute("VACUUM")
                }
                target.execute("PRAGMA foreign_keys=OFF")

                target.transaction {
                    FrozenV3Ddl.forEach { target.execute(it) }
                    target.execute("PRAGMA main.user_version=$TargetSchemaVersion")
                    target.execute("PRAGMA main.application_id=$FrozenApplicationId")
                }

                target.execute("ATTACH DATABASE ? AS src", listOf(database.toString()))
                try {
                    target.transaction {
                        for ((table, columns) in CopiedTables) {
                            val orderBy = if (table == "agent_commits") """ ORDER BY agent_id, "index", type""" else ""
                            target.execute("INSERT INTO main.$table($columns) SELECT $columns FROM src.$table$orderBy")
                        }
                        target.execute("DELETE FROM main.sqlite_sequence")
                        target.execute("INSERT INTO main.sqlite_sequence(name, seq) SELECT name, seq FROM src.sqlite_sequence")
                    }

                    for ((table, _) in CopiedTables) {
                        val expected = target.query("SELECT count(*) FROM src.$table").single().single()
                        val actual = target.query("SELECT count(*) FROM main.$table").single().single()
                        check(actual == expected) {
                            "Row count mismatch while migrating $table to 0.8.1: expected $expected, got $actual."
                        }
                    }
                    val expectedSeq = target.query("SELECT name, seq FROM src.sqlite_sequence ORDER BY name")
                    val actualSeq = target.query("SELECT name, seq FROM main.sqlite_sequence ORDER BY name")
                    check(actualSeq == expectedSeq) {
                        "sqlite_sequence mismatch while migrating to 0.8.1: expected $expectedSeq, got $actualSeq."
                    }
                } finally {
                    target.execute("DETACH DATABASE src")
                }

                target.execute("PRAGMA foreign_keys=ON")
                check(target.query("PRAGMA main.foreign_key_check").isEmpty()) {
                    "Foreign key check failed on staged 0.8.1 database."
                }
                check(target.query("PRAGMA main.quick_check") == listOf(listOf("ok"))) {
                    "SQLite quick_check failed on staged 0.8.1 database."
                }
                check(target.query("PRAGMA main.page_size") == listOf(listOf(TargetPageSize))) {
                    "Staged 0.8.1 database did not adopt page_size=$TargetPageSize."
                }
                check(target.query("PRAGMA main.user_version") == listOf(listOf(TargetSchemaVersion))) {
                    "Staged 0.8.1 database has unexpected user_version."
                }
                check(target.query("PRAGMA main.application_id") == listOf(listOf(FrozenApplicationId))) {
                    "Staged 0.8.1 database has unexpected application_id."
                }
                check(target.schemaStatements() == FrozenV3Schema) {
                    "Staged 0.8.1 database schema does not match frozen v3 DDL."
                }
            }
        }

        check(StagingDatabaseFiles.drop(1).none { fileSystem.exists(Path(home, it)) }) {
            "SQLite left staging sidecars after closing staged 0.8.1 database."
        }
        check(SourceSidecarFiles.none { fileSystem.exists(Path(home, it)) }) {
            "Source SQLite sidecars reappeared before 0.8.1 atomic cutover."
        }
        fileSystem.atomicMove(staging, database)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            runCatching { cleanupStagingFiles(home, fileSystem) }
        }
        throw failure
    }
}

private suspend fun cleanupStagingFiles(home: Path, fileSystem: CoroutineFileSystem) {
    for (name in StagingDatabaseFiles) {
        fileSystem.delete(Path(home, name), mustExist = false)
    }
}

private fun SqliteConnection.schemaStatements(): Set<Any?> =
    query("SELECT sql FROM main.sqlite_schema WHERE sql IS NOT NULL").map { it.single() }.toSet()
