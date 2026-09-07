package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.app.migration.v0_3_3.migrateToV0_3_3
import io.github.stream29.kodex.app.migration.v0_3_5.migrateToV0_3_5
import io.github.stream29.kodex.app.migration.v0_4_3.migrateToV0_4_3

internal val KodexHomeMigrations: List<Migration> = listOf(
    Migration(
        toVersion = MigrationVersion("0.3.3"),
        action = ::migrateToV0_3_3,
    ),
    Migration(
        toVersion = MigrationVersion("0.3.5"),
        action = ::migrateToV0_3_5,
    ),
    Migration(
        toVersion = MigrationVersion("0.4.3"),
        action = ::migrateToV0_4_3,
    ),
)
