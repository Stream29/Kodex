package io.github.stream29.kodex.app.migration.v0_9_0

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
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
import io.github.stream29.kodex.app.migration.requireFrozenHomeSchema
import io.github.stream29.kodex.app.migration.writeFixtureCheckpoint
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val fs = homeTestFileSystem
private val v081 = MigrationVersion("0.8.1")
private val v090 = MigrationVersion("0.9.0")
private val v091 = MigrationVersion("0.9.1")

private val allMirrorTimelines = listOf(
    "index",
    "work",
    "settings",
    "timestamp",
    "token-count",
    "unstable",
    "steer",
)

public val migrateToV0_9_0Test by testSuite {
    testFixture { newTestHome() } closeWith {
        deleteTestHome(this)
    } asParameterForEach {
        test("retires root and child mirror layouts and removes empty sessions directory while preserving Home files and SQLite data") { home ->
            createValidV081Home(home)
            writeMirrorTree(Path(home, "sessions", "0"), includeArchiveMark = true)
            writeMirrorTree(Path(home, "sessions", "0", "agents", "42"), includeArchiveMark = false)
            writeMirrorTree(Path(home, "sessions", "7"), includeArchiveMark = false)
            fs.writeString(Path(home, "AGENTS.md"), "# Keep me\n")
            fs.writeString(Path(home, "settings.yml"), "daemonTtlSeconds: -1\n")
            fs.createDirectories(Path(home, "skills", "kodex-home"))
            fs.writeString(Path(home, "skills", "kodex-home", "SKILL.md"), "# legacy kodex-home\n")
            fs.createDirectories(Path(home, "skills", "my-custom"))
            fs.writeString(Path(home, "skills", "my-custom", "SKILL.md"), "# keep custom skill\n")
            fs.createDirectories(Path(home, "generated_images", "session-0"))
            fs.writeString(Path(home, "generated_images", "session-0", "art.png"), "png-bytes")

            val started = mutableListOf<Pair<MigrationVersion, MigrationVersion>>()
            val handle = prepareKodexHome(
                home = home,
                currentVersion = v091,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
                onMigrationStarted = { from, to ->
                    started += from to to
                    if (to == v091) {
                        openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { it.requireFrozenHomeSchema(4) }
                    }
                },
            )
            try {
                assertEquals(listOf(v081 to v090, v090 to v091), started)
                assertEquals(v091, handle.version)
                assertEquals(readyMarker("0.9.1"), fs.readString(Path(home, HomeVersionFile)))
                assertFalse(fs.exists(Path(home, "sessions")))
                assertFalse(fs.exists(Path(home, "skills", "kodex-home")))
                assertEquals("# keep custom skill\n", fs.readString(Path(home, "skills", "my-custom", "SKILL.md")))
                assertEquals("# Keep me\n", fs.readString(Path(home, "AGENTS.md")))
                assertEquals("daemonTtlSeconds: -1\n", fs.readString(Path(home, "settings.yml")))
                assertEquals("png-bytes", fs.readString(Path(home, "generated_images", "session-0", "art.png")))
            } finally {
                handle.closeAndJoin()
            }

            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                assertEquals(5L, db.query("PRAGMA main.user_version").single().single())
                assertEquals(listOf(listOf(0L)), db.query("SELECT session_id FROM sessions"))
                assertTrue(db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'fork_context_recovery'").isEmpty())
            }
        }

        test("preserves unknown files, non-canonical filenames, child archive.mark, and legacy subagents directories") { home ->
            createValidV081Home(home)
            val root0 = Path(home, "sessions", "0")
            val child1 = Path(root0, "agents", "1")
            writeMirrorTree(root0, includeArchiveMark = true)
            writeMirrorTree(child1, includeArchiveMark = true) // child archive.mark is unknown, must be preserved

            val preservedFiles = listOf(
                Path(home, "sessions", "readme.txt"),
                Path(root0, "subagents", "legacy.json"),
                Path(root0, "custom-note.md"),
                Path(root0, "index", "01.json"),
                Path(root0, "index", "0.json.tmp"),
                Path(root0, "index", "note.txt"),
                Path(child1, "archive.mark"),
                Path(child1, "work", "extra.json"),
                Path(home, "sessions", "01", "index", "0.json"),
            )
            for (path in preservedFiles) {
                fs.createDirectories(checkNotNull(path.parent))
                fs.writeString(path, "keep:${path.name}")
            }

            prepareKodexHome(
                home = home,
                currentVersion = v091,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
            ).closeAndJoin()

            assertEquals(readyMarker("0.9.1"), fs.readString(Path(home, HomeVersionFile)))
            // Known mirror files must be deleted.
            assertFalse(fs.exists(Path(root0, "archive.mark")))
            assertFalse(fs.exists(Path(root0, "index", "0.json")))
            assertFalse(fs.exists(Path(root0, "index", "latest.json")))
            assertFalse(fs.exists(Path(root0, "work")))
            assertFalse(fs.exists(Path(child1, "index")))
            assertFalse(fs.exists(Path(child1, "work", "0.json")))
            // All unknown files and non-canonical directories must remain intact.
            for (path in preservedFiles) {
                assertTrue(fs.exists(path), "Expected preserved path to exist: $path")
                assertEquals("keep:${path.name}", fs.readString(path))
            }
        }

        test("is idempotent when sessions directory is absent or partially cleaned") { home ->
            createValidV081Home(home)
            // First run with partially populated sessions/ (simulating interrupted cleanup).
            fs.createDirectories(Path(home, "sessions", "3", "index"))
            fs.writeString(Path(home, "sessions", "3", "index", "0.json"), "{}")
            fs.createDirectories(Path(home, "sessions", "3", "agents", "9"))

            migrateToV0_9_0(home, fs)
            assertFalse(fs.exists(Path(home, "sessions")))

            // Second run with sessions/ already absent still succeeds via prepareKodexHome.
            prepareKodexHome(
                home = home,
                currentVersion = v091,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
            ).closeAndJoin()

            assertEquals(readyMarker("0.9.1"), fs.readString(Path(home, HomeVersionFile)))
            assertFalse(fs.exists(Path(home, "sessions")))
        }

        test("fails closed with zero mirror deletions when kodex.db is missing or invalid") { home ->
            writeMirrorTree(Path(home, "sessions", "0"), includeArchiveMark = true)
            val marker = Path(home, "sessions", "0", "archive.mark")
            val record = Path(home, "sessions", "0", "index", "0.json")

            // Missing database
            assertFailsWith<KodexHomeVersionException> {
                migrateToV0_9_0(home, fs)
            }
            assertTrue(fs.exists(marker))
            assertTrue(fs.exists(record))

            // Corrupt database schema
            openSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                db.execute("PRAGMA main.application_id = 1262770224")
                db.execute("CREATE TABLE unrelated(id INTEGER PRIMARY KEY)")
            }
            assertFailsWith<KodexHomeVersionException> {
                migrateToV0_9_0(home, fs)
            }
            assertTrue(fs.exists(marker))
            assertTrue(fs.exists(record))
        }

        test("stays inactive while application version is still 0.8.1 on a current-schema Home") { home ->
            openSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                db.execute("PRAGMA journal_mode=DELETE")
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions(session_id) VALUES (0)")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES (0, 0)")
                db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES (0)")
            }
            writeFixtureCheckpoint(home, "0.8.1")
            writeMirrorTree(Path(home, "sessions", "0"), includeArchiveMark = true)

            prepareKodexHome(
                home = home,
                currentVersion = v081,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
            ).closeAndJoin()

            assertEquals(readyMarker("0.8.1"), fs.readString(Path(home, HomeVersionFile)))
            assertTrue(fs.exists(Path(home, "sessions", "0", "index", "0.json")))
        }
    }
}

private suspend fun createValidV081Home(home: Path) {
    openSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
        db.execute("PRAGMA journal_mode=DELETE")
        db.initializeFrozenHomeSchema(3)
        db.execute("INSERT INTO sessions(session_id) VALUES (0)")
        db.execute("INSERT INTO agents(agent_id, session_id) VALUES (0, 0)")
        db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES (0)")
    }
    writeFixtureCheckpoint(home, "0.8.1")
}

private suspend fun writeMirrorTree(ownerDir: Path, includeArchiveMark: Boolean) {
    fs.createDirectories(ownerDir)
    if (includeArchiveMark) {
        fs.writeString(Path(ownerDir, "archive.mark"), "")
    }
    for (timeline in allMirrorTimelines) {
        val dir = Path(ownerDir, timeline)
        fs.createDirectories(dir)
        fs.writeString(Path(dir, "latest.json"), "1")
        fs.writeString(Path(dir, "0.json"), "{}")
        fs.writeString(Path(dir, "1.json"), "{}")
    }
}
