package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.app.migration.v0_8_1.migrateToV0_8_1

internal val KodexHomeMigrations: List<Migration> = listOf(
    Migration(
        toVersion = MigrationVersion("0.8.1"),
        action = ::migrateToV0_8_1,
    ),
)
