package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.app.migration.v0_3_3.migrateToV0_3_3

internal val KodexHomeMigrations: List<Migration> = listOf(
    Migration(
        toVersion = MigrationVersion("0.3.3"),
        action = ::migrateToV0_3_3,
    ),
)
