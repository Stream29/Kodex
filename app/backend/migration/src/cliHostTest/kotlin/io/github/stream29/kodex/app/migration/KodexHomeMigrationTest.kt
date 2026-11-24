package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val fs = homeTestFileSystem

public val kodexHomeMigrationTest by testSuite {
    test("application version is generated and the product registry lists expected migrations") {
        val version = CurrentKodexApplicationVersion
        assertEquals(MigrationVersion(GeneratedKodexApplicationVersion), version)
        assertSame(version, CurrentKodexApplicationVersion)
        assertEquals(listOf(MigrationVersion("0.8.1")), KodexHomeMigrations.map { it.toVersion })
    }

    testFixture { newTestHome() } closeWith {
        deleteTestHome(this)
    } asParameterForEach {
        test("preflight does not create a missing root or lease files") { directory ->
            val home = Path(directory, "missing")
            preflightKodexHome(home)
            assertTrue(fs.list(directory).isEmpty())
            run {
                val handle = prepareKodexHome(home)
                try {
                    assertEquals(CurrentKodexApplicationVersion, handle.version)
                    assertEquals(readyMarker("$CurrentKodexApplicationVersion"), fs.readString(Path(home, HomeVersionFile)))
                    assertTrue(fs.metadataOrNull(Path(home, HomeDatabaseFile))!!.size > 0)
                    assertFalse(fs.exists(Path(home, "sessions")))
                    assertFalse(fs.exists(Path(home, "settings.yml")))
                } finally {
                    handle.closeAndJoin()
                }
                openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                    assertEquals(16384L, db.query("PRAGMA main.page_size").single().single())
                }
            }
        }

        for (entry in listOf(
            "settings.yml", "sessions", "log", ".locks/unknown",
            ".locks/backend/connection.json", ".locks/backend/backend-startup.log",
        )) {
            test("rejects unmarked Home containing $entry without changes") { home ->
                val path = entry.split('/').fold(home) { parent, name -> Path(parent, name) }
                if (entry == "sessions" || entry == "log") {
                    fs.createDirectories(path)
                } else {
                    fs.createDirectories(checkNotNull(path.parent))
                    fs.writeString(path, "existing content")
                }
                val before = homeSnapshot(home)
                assertFailsWith<KodexHomeLayoutException> { preflightKodexHome(home) }
                val failure = assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
                assertContains(failure.message.orEmpty(), "different, empty Kodex Home")
                assertEquals(before, homeSnapshot(home))
            }
        }

        for (legacy in listOf(null, "\"0.3.2\"", "\"0.7.3\"", "\"0.7.5\"")) {
            test("rejects a legacy SQLite Home with marker $legacy without touching WAL or the database") { home ->
                val database = Path(home, HomeDatabaseFile)
                openSqliteConnection(database).use { connection ->
                    connection.query("PRAGMA journal_mode=WAL")
                    connection.execute("CREATE TABLE old_sessions(id INTEGER PRIMARY KEY)")
                    connection.execute("INSERT INTO old_sessions VALUES(1)")
                    if (legacy != null) fs.writeString(Path(home, HomeVersionFile), legacy)
                    val before = homeSnapshot(home)
                    assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home) }
                    assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
                    assertEquals(before, homeSnapshot(home))
                }
            }
        }

        for (marker in listOf(
            """{"generation":2,"checkpoint":"0.1.0"}""",
            """{"generation":1,"generation":1,"checkpoint":"0.1.0"}""",
            """{"generation":"1","checkpoint":"0.1.0"}""",
            """{"generation":1,"checkpoint":"01.0.0"}""",
            """{"generation":1,"checkpoint":"999999.0.0"}""",
            """{"generation":1,"checkpoint":"0.1.0","state":"ready"}""",
            """{"generation":1,"checkpoint":"0.1.0","state":"initializing","extra":1}""",
        )) {
            test("rejects incompatible marker $marker without writing") { home ->
                fs.writeString(Path(home, HomeVersionFile), marker)
                assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home) }
                assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
                assertEquals(mapOf("$HomeVersionFile" to marker.encodeToByteArray().toList()), homeSnapshot(home))
            }
        }

        test("refuses a ready marker without a signed database") { home ->
            writeFixtureCheckpoint(home, "$CurrentKodexApplicationVersion")
            assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home) }
            fs.writeString(Path(home, HomeDatabaseFile), "not a SQLite database".repeat(40))
            val before = homeSnapshot(home)
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
            assertEquals(before, homeSnapshot(home))
        }

        test("accepts the owned backend instance lock as fresh infrastructure") { home ->
            val backendLocks = Path(home, ".locks", "backend")
            fs.createDirectories(backendLocks)
            fs.writeString(Path(backendLocks, "instance.lock"), "")
            val before = homeSnapshot(home)
            preflightKodexHome(home)
            assertEquals(before, homeSnapshot(home))
            prepareKodexHome(home).closeAndJoin()
            assertTrue(fs.exists(Path(backendLocks, "instance.lock")))
        }

        for (failedPublication in listOf("pending marker", "database", "ready marker")) {
            test("recovers after interruption publishing the $failedPublication") { home ->
                val failure = IOException("isolated publication failure")
                var markerMoves = 0
                val interrupted = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (destination.name == HomeVersionFile) {
                            markerMoves++
                            if (failedPublication == "pending marker" && markerMoves == 1 ||
                                failedPublication == "ready marker" && markerMoves == 2
                            ) throw failure
                        }
                        if (destination.name == HomeDatabaseFile && failedPublication == "database") throw failure
                        fs.atomicMove(source, destination)
                    }
                }
                assertSame(failure, assertFailsWith<IOException> { prepareKodexHome(home, interrupted) })
                val marker = Path(home, HomeVersionFile)
                val database = Path(home, HomeDatabaseFile)
                if (failedPublication == "pending marker") {
                    assertFalse(fs.exists(marker))
                } else {
                    assertEquals(initializingMarker("$CurrentKodexApplicationVersion"), fs.readString(marker))
                }
                val published = if (failedPublication == "ready marker") fs.readBytes(database).toList() else null
                assertEquals(published != null, fs.exists(database))
                val before = homeSnapshot(home)
                preflightKodexHome(home)
                assertEquals(before, homeSnapshot(home))
                prepareKodexHome(home).closeAndJoin()
                assertEquals(readyMarker("$CurrentKodexApplicationVersion"), fs.readString(marker))
                if (published != null) assertEquals(published, fs.readBytes(database).toList())
                assertTrue(StagingDatabaseFiles.none { fs.exists(Path(home, it)) })
            }
        }

        test("does not recover an initializing marker alongside a business directory") { home ->
            fs.writeString(Path(home, HomeVersionFile), initializingMarker("$CurrentKodexApplicationVersion"))
            fs.createDirectories(Path(home, "sessions"))
            val before = homeSnapshot(home)
            assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home) }
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
            assertEquals(before, homeSnapshot(home))
        }

        test("does not claim a published database with business data during initialization") { home ->
            prepareKodexHome(home).closeAndJoin()
            val marker = Path(home, HomeVersionFile)
            val database = Path(home, HomeDatabaseFile)
            openExistingSqliteConnection(database).use { it.execute("INSERT INTO sessions VALUES(1)") }
            fs.writeString(marker, initializingMarker("$CurrentKodexApplicationVersion"))
            val before = homeSnapshot(home)
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
            assertEquals(before, homeSnapshot(home))
        }

        test("does not finish a recognizable initialization using another application version") { home ->
            fs.writeString(Path(home, HomeVersionFile), initializingMarker("0.0.0"))
            val before = homeSnapshot(home)
            val failure = assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
            assertContains(failure.message.orEmpty(), "finish it with that version")
            assertEquals(before, homeSnapshot(home))
        }

        test("ready WAL database preflight cannot create SHM or open SQLite") { home ->
            prepareKodexHome(home).closeAndJoin()
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { connection ->
                assertEquals(listOf(listOf("wal")), connection.query("PRAGMA journal_mode=WAL"))
            }
            val before = homeSnapshot(home)
            assertFalse(before.containsKey("$HomeDatabaseFile-shm"))
            preflightKodexHome(home)
            assertEquals(before, homeSnapshot(home))
            prepareKodexHome(home).closeAndJoin()
        }

        test("ready accepts existing backend endpoint files but rejects an invalid lock path") { home ->
            prepareKodexHome(home).closeAndJoin()
            val directory = Path(home, ".locks", "backend")
            fs.createDirectories(directory)
            fs.writeString(Path(directory, "connection.json"), "ready endpoint")
            fs.writeString(Path(directory, ".connection.synthetic.tmp"), "endpoint temporary")
            preflightKodexHome(home)
            fs.createDirectories(Path(directory, "instance.lock"))
            val before = homeSnapshot(home)
            assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home) }
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
            assertEquals(before, homeSnapshot(home))
        }

        test("controlled prepare rejects a signed database with an invalid current schema") { home ->
            prepareKodexHome(home).closeAndJoin()
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { it.execute("PRAGMA user_version=999") }
            val before = homeSnapshot(home)
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home) }
            assertEquals(before, homeSnapshot(home))
        }
    }
}

private suspend fun homeSnapshot(path: Path, prefix: String = ""): Map<String, List<Byte>?> =
    buildMap {
        for (entry in fs.list(path)) {
            val name = "$prefix${entry.name}"
            if (fs.metadataOrNull(entry)!!.isDirectory) {
                put(name, null)
                putAll(homeSnapshot(entry, "$name/"))
            } else {
                put(name, fs.readBytes(entry).toList())
            }
        }
    }
