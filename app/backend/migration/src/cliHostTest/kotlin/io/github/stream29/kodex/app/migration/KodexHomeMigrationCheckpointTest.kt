package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.SqliteSchemaVersion
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.filesystemlease.FileSystemReadLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val fs = homeTestFileSystem
private val latest = MigrationVersion("3.0.0")

public val kodexHomeMigrationCheckpointTest by testSuite {
    testFixture { readyTestHome() } closeWith { deleteTestHome(this) } asParameterForEach {
        test("no-op reopens retain the marker bytes and never acquire a writer or scan Session entries") { home ->
            val versionPath = Path(home, HomeVersionFile)
            val original = """{ "checkpoint": "1.0.0", "generation": 1 }"""
            fs.writeString(versionPath, original)
            fs.writeString(Path(home, "sessions"), "unknown content")
            val guarded = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    check(!path.name.endsWith(".write.lock"))
                    fs.writeString(path, content, append, mustCreate)
                }
                override suspend fun atomicMove(source: Path, destination: Path) {
                    check(destination != versionPath)
                    fs.atomicMove(source, destination)
                }
                override suspend fun list(directory: Path): Collection<Path> {
                    check(directory.name != "sessions")
                    return fs.list(directory)
                }
            }
            val future = Migration(MigrationVersion("4.0.0")) { _, _ -> error("Future migration ran") }
            for (version in listOf(latest, MigrationVersion("2.0.0"))) {
                val handle = prepareKodexHome(home, version, listOf(future), guarded)
                try {
                    assertEquals(MigrationVersion("1.0.0"), handle.version)
                    assertEquals(original, fs.readString(versionPath))
                } finally {
                    handle.closeAndJoin()
                }
            }
        }

        test("executes only eligible targets and resumes from the last atomic checkpoint") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val calls = mutableListOf<String>()
            val starts = mutableListOf<String>()
            val primary = IOException("synthetic migration interrupted")
            val interim = Path(home, "synthetic-migration-output")
            val migrations = listOf(
                Migration(MigrationVersion("0.9.0")) { _, _ -> error("Already completed target ran") },
                Migration(MigrationVersion("1.1.0")) { _, _ -> calls += "1.1.0" },
                Migration(MigrationVersion("2.0.0")) { _, fileSystem ->
                    calls += "2.0.0"
                    if (!fileSystem.exists(interim)) {
                        fileSystem.writeString(interim, "in progress")
                        throw primary
                    }
                    fileSystem.writeString(interim, "completed")
                },
                Migration(MigrationVersion("4.0.0")) { _, _ -> error("Future target ran") },
            )
            val onStart: (MigrationVersion, MigrationVersion) -> Unit = { from, to -> starts += "$from -> $to" }
            assertSame(primary, assertFailsWith<IOException> {
                prepareKodexHome(home, latest, migrations, fs, onStart)
            })
            assertEquals(readyMarker("1.1.0"), fs.readString(Path(home, HomeVersionFile)))
            assertTrue(fs.list(homeLeaseDirectory(home)).isEmpty())
            val resumed = prepareKodexHome(home, latest, migrations, fs, onStart)
            try {
                assertEquals(MigrationVersion("2.0.0"), resumed.version)
                assertEquals(readyMarker("2.0.0"), fs.readString(Path(home, HomeVersionFile)))
                assertEquals("completed", fs.readString(interim))
                assertEquals(listOf("1.1.0", "2.0.0", "2.0.0"), calls)
                assertEquals(listOf("1.0.0 -> 1.1.0", "1.1.0 -> 2.0.0", "1.1.0 -> 2.0.0"), starts)
            } finally {
                resumed.closeAndJoin()
            }
        }

        test("a same-generation older schema can enter its migration, but not open with no action") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val database = Path(home, HomeDatabaseFile)
            openExistingSqliteConnection(database).use { it.execute("PRAGMA user_version=987") }
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home, latest, emptyList(), fs) }
            val target = MigrationVersion("2.0.0")
            val migration = Migration(target) { path, _ ->
                openExistingSqliteConnection(Path(path, HomeDatabaseFile)).use { db ->
                    assertEquals(listOf(listOf(987L)), db.query("PRAGMA user_version"))
                    db.execute("PRAGMA user_version=$SqliteSchemaVersion")
                }
            }
            val handle = prepareKodexHome(home, latest, listOf(migration), fs)
            try {
                assertEquals(target, handle.version)
            } finally {
                handle.closeAndJoin()
            }
        }

        test("a last action that returns without producing the current schema cannot advance its checkpoint") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val migration = Migration(MigrationVersion("2.0.0")) { path, _ ->
                openExistingSqliteConnection(Path(path, HomeDatabaseFile)).use { it.execute("PRAGMA user_version=987") }
            }
            assertFailsWith<KodexHomeVersionException> { prepareKodexHome(home, latest, listOf(migration), fs) }
            assertEquals(readyMarker("1.0.0"), fs.readString(Path(home, HomeVersionFile)))
        }

        test("a failing start callback cannot run the action or advance the checkpoint") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val primary = IOException("migration callback failed")
            val migration = Migration(MigrationVersion("2.0.0")) { _, _ -> error("Action ran") }
            assertSame(primary, assertFailsWith<IOException> {
                prepareKodexHome(home, latest, listOf(migration), fs) { _, _ -> throw primary }
            })
            assertEquals(readyMarker("1.0.0"), fs.readString(Path(home, HomeVersionFile)))
            assertTrue(fs.list(homeLeaseDirectory(home)).isEmpty())
        }

        test("an interrupted checkpoint publication preserves the previous marker and retries the action") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val primary = IOException("isolated checkpoint publication failure")
            var attempts = 0
            val interrupted = object : CoroutineFileSystem by fs {
                override suspend fun atomicMove(source: Path, destination: Path) {
                    if (destination.name == HomeVersionFile) throw primary
                    fs.atomicMove(source, destination)
                }
            }
            val migration = Migration(MigrationVersion("2.0.0")) { _, _ -> attempts++ }
            assertSame(primary, assertFailsWith<IOException> {
                prepareKodexHome(home, latest, listOf(migration), interrupted)
            })
            assertEquals(readyMarker("1.0.0"), fs.readString(Path(home, HomeVersionFile)))
            assertTrue(fs.list(homeLeaseDirectory(home)).isEmpty())
            prepareKodexHome(home, latest, listOf(migration), fs).closeAndJoin()
            assertEquals(readyMarker("2.0.0"), fs.readString(Path(home, HomeVersionFile)))
            assertEquals(2, attempts)
        }

        test("rejects out-of-order and duplicate registry targets") { home ->
            val migrations = listOf("2.0.0", "1.0.0").map { version ->
                Migration(MigrationVersion(version)) { _, _ -> error("Invalid registry ran") }
            }
            for (registry in listOf(migrations, listOf(migrations.first(), migrations.first()))) {
                val error = assertFailsWith<IllegalStateException> { prepareKodexHome(home, latest, registry, fs) }
                assertContains(error.message.orEmpty(), "unique and strictly ordered")
            }
        }

        test("a retained ready reader blocks exclusive migration and sees the original marker") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val reader = prepareKodexHome(home, latest, emptyList(), fs)
            val migration = Migration(MigrationVersion("2.0.0")) { _, _ -> }
            try {
                val failure = assertFailsWith<KodexHomeInUseException> {
                    prepareKodexHome(home, latest, listOf(migration), fs)
                }
                assertContains(failure.message.orEmpty(), "needs data migration from 1.0.0 to 2.0.0")
                assertContains(failure.message.orEmpty(), "Close all other Kodex instances")
                assertTrue(failure.cause is FileSystemLeaseInUseException)
                assertEquals(readyMarker("1.0.0"), fs.readString(Path(home, HomeVersionFile)))
            } finally {
                reader.closeAndJoin()
            }
            prepareKodexHome(home, latest, listOf(migration), fs).closeAndJoin()
            assertEquals(readyMarker("2.0.0"), fs.readString(Path(home, HomeVersionFile)))
        }

        test("rereads the checkpoint under the writer and skips an already completed action") { home ->
            writeFixtureCheckpoint(home, "1.0.0")
            val duringWriteAcquisition = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    fs.writeString(path, content, append, mustCreate)
                    if (path.name.endsWith(".write.lock")) writeFixtureCheckpoint(home, "2.0.0")
                }
            }
            val migration = Migration(MigrationVersion("2.0.0")) { _, _ -> error("Already completed action ran") }
            val handle = prepareKodexHome(home, latest, listOf(migration), duringWriteAcquisition)
            try {
                assertEquals(MigrationVersion("2.0.0"), handle.version)
            } finally {
                handle.closeAndJoin()
            }
        }

        for (handoffVersion in listOf("1.2.0", "1.0.0", "4.0.0", null)) {
            test("rechecks checkpoint ${handoffVersion ?: "missing"} after writer handoff") { home ->
                writeFixtureCheckpoint(home, "1.0.0")
                val duringWriteRelease = object : CoroutineFileSystem by fs {
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        fs.delete(path, mustExist)
                        if (path.name.endsWith(".write.lock")) {
                            if (handoffVersion == null) fs.delete(Path(home, HomeVersionFile))
                            else writeFixtureCheckpoint(home, handoffVersion)
                        }
                    }
                }
                val migrations = listOf(Migration(MigrationVersion("1.1.0")) { _, _ -> })
                if (handoffVersion == "1.2.0") {
                    val handle = prepareKodexHome(home, latest, migrations, duringWriteRelease)
                    try {
                        assertEquals(MigrationVersion("1.2.0"), handle.version)
                    } finally {
                        handle.closeAndJoin()
                    }
                } else {
                    assertFailsWith<KodexHomeVersionException> {
                        prepareKodexHome(home, latest, migrations, duringWriteRelease)
                    }
                }
                assertTrue(fs.list(homeLeaseDirectory(home)).isEmpty())
            }
        }
    }

    testFixture { newTestHome() } closeWith { deleteTestHome(this) } asParameterForEach {
        test("fresh initializes directly at the running version without replaying earlier migrations") { home ->
            val migrations = listOf("1.0.0", "4.0.0").map { version ->
                Migration(MigrationVersion(version)) { _, _ -> error("Fresh Home replayed migration $version") }
            }
            val handle = prepareKodexHome(home, latest, migrations, fs)
            try {
                assertEquals(latest, handle.version)
                assertEquals(readyMarker("$latest"), fs.readString(Path(home, HomeVersionFile)))
            } finally {
                handle.closeAndJoin()
            }
        }

        test("reader of a fresh root blocks initialization without publishing any data") { home ->
            val lease = FileSystemReadLease(homeLeaseDirectory(home))
            try {
                val failure = assertFailsWith<KodexHomeInUseException> { prepareKodexHome(home) }
                assertContains(failure.message.orEmpty(), "needs initialization")
                assertFalse(fs.exists(Path(home, HomeVersionFile)))
                assertFalse(fs.exists(Path(home, HomeDatabaseFile)))
            } finally {
                lease.closeAndJoin()
            }
            prepareKodexHome(home).closeAndJoin()
        }
    }
}
