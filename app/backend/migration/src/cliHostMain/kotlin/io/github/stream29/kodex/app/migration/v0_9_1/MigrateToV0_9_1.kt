package io.github.stream29.kodex.app.migration.v0_9_1

import io.github.stream29.kodex.app.migration.FrozenCacheNonceColumn
import io.github.stream29.kodex.app.migration.GenerationOneApplicationId
import io.github.stream29.kodex.app.migration.HomeDatabaseFile
import io.github.stream29.kodex.app.migration.inspectHomeRead
import io.github.stream29.kodex.app.migration.requireFrozenHomeSchema
import io.github.stream29.kodex.app.migration.requireHomeDatabaseHeader
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal suspend fun migrateToV0_9_1(home: Path, fileSystem: CoroutineFileSystem) {
    requireHomeDatabaseHeader(home, fileSystem)
    inspectHomeRead(home, "Unable to migrate the Kodex Home database to 0.9.1") {
        withContext(Dispatchers.IO) {
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                check(db.query("PRAGMA main.application_id") == listOf(listOf(GenerationOneApplicationId))) {
                    "Unrecognized Kodex SQLite database signature."
                }
                val version = db.query("PRAGMA main.user_version").single().single()
                when (version) {
                    4L -> db.transaction {
                        db.requireFrozenHomeSchema(4)
                        // The canonical v5 DDL uses the exact text produced by ADD COLUMN.
                        // This keeps the original agents table, dependent FKs and ID sequences.
                        db.execute("ALTER TABLE main.agents ADD COLUMN $FrozenCacheNonceColumn")
                        db.execute("PRAGMA main.user_version=5")
                        db.requireFrozenHomeSchema(5)
                        check(db.query("PRAGMA main.foreign_key_check").isEmpty()) {
                            "Foreign key check failed during 0.9.1 migration."
                        }
                    }
                    5L -> {
                        db.requireFrozenHomeSchema(5)
                        check(db.query("PRAGMA main.foreign_key_check").isEmpty()) {
                            "Foreign key check failed on the migrated 0.9.1 database."
                        }
                    }
                    else -> error("Unexpected Kodex SQLite schema version during 0.9.1 migration (user_version=$version).")
                }
            }
        }
    }
}
