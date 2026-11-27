package io.github.stream29.kodex.app.migration.v0_9_1

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.requireKodexHomeDatabase
import io.github.stream29.kodex.app.migration.HomeDatabaseFile
import io.github.stream29.kodex.app.migration.HomeVersionFile
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.KodexHomeVersionException
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.deleteTestHome
import io.github.stream29.kodex.app.migration.homeTestFileSystem
import io.github.stream29.kodex.app.migration.initializeFrozenHomeSchema
import io.github.stream29.kodex.app.migration.newTestHome
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.readyMarker
import io.github.stream29.kodex.app.migration.writeFixtureCheckpoint
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.files.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val fs = homeTestFileSystem
private val v081 = MigrationVersion("0.8.1")
private val v090 = MigrationVersion("0.9.0")
private val v091 = MigrationVersion("0.9.1")

val migrateToV0_9_1Test by testSuite {
    testFixture { newTestHome() } closeWith { deleteTestHome(this) } asParameterForEach {
        test("v4 upgrades in place with exact v5 DDL, unchanged data, sequences, and foreign keys") { home ->
            val database = Path(home, HomeDatabaseFile)
            val before = openSqliteConnection(database).use { db ->
                db.initializeFrozenHomeSchema(4)
                db.seedAgentTree()
                db.businessRows()
            }
            writeFixtureCheckpoint(home, "$v090")

            prepareKodexHome(home, v091, KodexHomeMigrations, fs).closeAndJoin()

            assertEquals(readyMarker("$v091"), fs.readString(Path(home, HomeVersionFile)))
            openExistingSqliteConnection(database).use { db ->
                requireKodexHomeDatabase(db)
                openInMemorySqliteConnection().use { fresh ->
                    initializeKodexHomeDatabase(fresh)
                    assertEquals(fresh.schemaDdl(), db.schemaDdl())
                }
                assertEquals(before, db.businessRows())
                assertEquals(listOf(listOf(1L), listOf(1L)), db.query("SELECT cache_nonce FROM agents ORDER BY agent_id"))
                assertTrue(db.query("PRAGMA main.foreign_key_check").isEmpty())
                assertEquals(
                    listOf(listOf(11L, 1L)),
                    db.query("INSERT INTO agents(session_id, parent_agent_id) VALUES (3, 1) RETURNING agent_id, cache_nonce"),
                )
                db.execute("DELETE FROM sessions WHERE session_id=3")
                for (table in listOf("agents", "agent_commits", "session_catalog_view")) {
                    assertTrue(db.query("SELECT 1 FROM $table").isEmpty(), table)
                }
                assertEquals(before.getValue("schedules"), db.businessRows().getValue("schedules"))
            }
        }

        test("reentry after the SQLite commit but before checkpoint publication preserves a newer nonce") { home ->
            val database = Path(home, HomeDatabaseFile)
            openSqliteConnection(database).use { db ->
                db.initializeFrozenHomeSchema(4)
                db.seedAgentTree()
            }
            writeFixtureCheckpoint(home, "$v090")
            migrateToV0_9_1(home, fs)
            openExistingSqliteConnection(database).use { it.execute("UPDATE agents SET cache_nonce=7 WHERE agent_id=1") }
            val before = fs.readBytes(database)

            assertEquals(readyMarker("$v090"), fs.readString(Path(home, HomeVersionFile)))
            prepareKodexHome(home, v091, KodexHomeMigrations, fs).closeAndJoin()

            assertEquals(readyMarker("$v091"), fs.readString(Path(home, HomeVersionFile)))
            assertContentEquals(before, fs.readBytes(database))
        }

        for ((version, signature) in listOf(3 to true, 6 to true, 4 to false, 5 to true)) {
            test("rejects invalid SQLite signature/schema (version=$version, signed=$signature) without writes") { home ->
                val database = Path(home, HomeDatabaseFile)
                openSqliteConnection(database).use { db ->
                    db.initializeFrozenHomeSchema(4)
                    db.execute("PRAGMA main.user_version=$version")
                    if (!signature) db.execute("PRAGMA main.application_id=123")
                }
                val before = fs.readBytes(database)
                assertFailsWith<KodexHomeVersionException> { migrateToV0_9_1(home, fs) }
                assertContentEquals(before, fs.readBytes(database))
            }
        }

        test("foreign key validation failure rolls back ALTER TABLE and user_version") { home ->
            val database = Path(home, HomeDatabaseFile)
            openSqliteConnection(database).use { db ->
                db.initializeFrozenHomeSchema(4)
                db.execute("PRAGMA foreign_keys=OFF")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES (1, 999)")
            }
            val before = fs.readBytes(database)
            assertFailsWith<KodexHomeVersionException> { migrateToV0_9_1(home, fs) }
            assertContentEquals(before, fs.readBytes(database))
        }

        test("0.8.1 -> 0.9.0 -> 0.9.1 applies both migrations in order and preserves Agent history") { home ->
            val database = Path(home, HomeDatabaseFile)
            val before = openSqliteConnection(database).use { db ->
                db.initializeFrozenHomeSchema(3)
                db.seedAgentTree()
                db.businessRows()
            }
            writeFixtureCheckpoint(home, "$v081")
            val started = mutableListOf<Pair<MigrationVersion, MigrationVersion>>()

            prepareKodexHome(home, v091, KodexHomeMigrations, fs) { from, to -> started += from to to }.closeAndJoin()

            assertEquals(listOf(v081 to v090, v090 to v091), started)
            assertEquals(readyMarker("$v091"), fs.readString(Path(home, HomeVersionFile)))
            openExistingSqliteConnection(database).use { db ->
                requireKodexHomeDatabase(db)
                assertEquals(before, db.businessRows())
            }
        }

        test("the 0.9.0 application version does not select the future 0.9.1 migration") { home ->
            val database = Path(home, HomeDatabaseFile)
            openSqliteConnection(database).use { it.initializeFrozenHomeSchema(4) }
            writeFixtureCheckpoint(home, "$v090")
            val before = fs.readBytes(database)
            assertFailsWith<KodexHomeVersionException> {
                prepareKodexHome(home, v090, KodexHomeMigrations, fs) { _, _ -> error("Future migration selected") }
            }
            assertEquals(readyMarker("$v090"), fs.readString(Path(home, HomeVersionFile)))
            assertContentEquals(before, fs.readBytes(database))
        }
    }
}

private fun SqliteConnection.seedAgentTree() {
    execute("INSERT INTO sessions VALUES (3)")
    execute("INSERT INTO agents(agent_id, session_id, parent_agent_id, completed) VALUES (1, 3, NULL, 0), (2, 3, 1, 1), (10, 3, 1, 1)")
    execute("DELETE FROM agents WHERE agent_id=10")
    execute("""INSERT INTO agent_commits VALUES (2, 'settings', 0, '{"model":"fixture"}', '2026-11-27T00:00:00Z')""")
    execute("INSERT INTO session_catalog_view (root_agent_id, name, cwd) VALUES (1, 'root', '/work')")
    execute("INSERT INTO session_groups VALUES ('/work', '/work', 4)")
    execute("INSERT INTO schedules VALUES (8, 1, 0, 0, NULL, 'once', '2026-11-27T00:00:00Z')")
    execute("UPDATE sqlite_sequence SET seq=23 WHERE name='schedules'")
}

private fun SqliteConnection.schemaDdl() =
    query("SELECT type, name, tbl_name, sql FROM main.sqlite_schema ORDER BY name")

private fun SqliteConnection.businessRows() = mapOf(
    "sessions" to query("SELECT * FROM sessions ORDER BY session_id"),
    "agents" to query("SELECT agent_id, session_id, parent_agent_id, completed FROM agents ORDER BY agent_id"),
    "agent_commits" to query("""SELECT rowid, * FROM agent_commits ORDER BY rowid"""),
    "session_catalog_view" to query("SELECT * FROM session_catalog_view ORDER BY root_agent_id"),
    "session_groups" to query("SELECT * FROM session_groups ORDER BY cwd_key"),
    "schedules" to query("SELECT * FROM schedules ORDER BY schedule_id"),
    "sqlite_sequence" to query("SELECT rowid, * FROM sqlite_sequence ORDER BY rowid"),
)
