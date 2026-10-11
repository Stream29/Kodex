package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.v0_3_5.KodexHomeSkill
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.filesystemlease.FileSystemReadLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public val kodexHomeMigrationTest by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("initializes a new Home at the last activated migration target") { home ->
            val expectedVersion = KodexHomeMigrations.lastOrNull {
                it.toVersion <= CurrentKodexApplicationVersion
            }?.toVersion ?: MigrationVersion("0.3.2")
            val handle = prepareKodexHome(home)
            try {
                assertEquals(
                    "\"$expectedVersion\"",
                    SystemCoroutineFileSystem.readString(Path(home, "version.json")),
                )
                assertEquals(expectedVersion, handle.version)
                assertFalse(SystemCoroutineFileSystem.exists(Path(home, "sessions")))
            } finally {
                handle.closeAndJoin()
            }
        }

        test("validates an unversioned Home without changing unknown data") { home ->
            val session = createEmptySession(home, 0)
            val legacy = Path(session, "subagents", "legacy.json")
            val unknown = Path(home, "user-data.txt")
            SystemCoroutineFileSystem.createDirectories(legacy.parent!!)
            SystemCoroutineFileSystem.writeString(legacy, "legacy")
            SystemCoroutineFileSystem.writeString(unknown, "user")

            prepareKodexHome(home).closeAndJoin()

            assertEquals("legacy", SystemCoroutineFileSystem.readString(legacy))
            assertEquals("user", SystemCoroutineFileSystem.readString(unknown))
        }

        test("does not version an invalid unversioned Home") { home ->
            val session = createEmptySession(home, 0)
            SystemCoroutineFileSystem.writeString(
                Path(session, "index", "latest.json"),
                "4",
            )

            assertFailsWith<KodexHomeLayoutException> {
                prepareKodexHome(home)
            }
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "version.json")))
        }

        test("does not scan Session data when the version already matches") { home ->
            SystemCoroutineFileSystem.writeString(
                Path(home, "version.json"),
                "\"$CurrentKodexApplicationVersion\"",
            )
            SystemCoroutineFileSystem.writeString(Path(home, "sessions"), "not-a-directory")
            var migrationStarted = false

            prepareKodexHome(home) { _, _ ->
                migrationStarted = true
            }.closeAndJoin()

            assertFalse(migrationStarted)
        }

        for (storedVersion in listOf("0.4.3", null)) {
            test("explains exclusive lease conflicts for ${storedVersion ?: "unversioned"} Home") { home ->
                val versionPath = Path(home, "version.json")
                if (storedVersion != null) {
                    SystemCoroutineFileSystem.writeString(versionPath, "\"$storedVersion\"")
                }
                val locks = Path(home, ".locks", "home")
                val reader = FileSystemReadLease(locks)
                val readOwners = homeLeaseOwners(locks)
                var migrationStarted = false
                try {
                    val failure = assertFailsWith<KodexHomeInUseException> {
                        prepareKodexHome(
                            home = home,
                            currentVersion = MigrationVersion("0.4.11"),
                            migrations = KodexHomeMigrations + Migration(MigrationVersion("0.4.12")) { _, _ ->
                                error("Future migration must not run")
                            },
                            fileSystem = SystemCoroutineFileSystem,
                            onMigrationStarted = { _, _ -> migrationStarted = true },
                        )
                    }
                    val message = failure.message.orEmpty()
                    assertTrue(message.contains(home.toString()))
                    assertTrue(message.contains("exclusive access"))
                    assertTrue(message.contains("Close all other Kodex instances using this Home, then retry."))
                    assertTrue(failure.cause is FileSystemLeaseInUseException)
                    assertTrue(message.contains(checkNotNull(failure.cause?.message)))
                    assertTrue(message.contains(readOwners.single().toString()))
                    assertFalse(message.contains("0.4.12"))
                    when (storedVersion) {
                        "0.4.3" -> {
                            assertTrue(message.contains("needs data migration from 0.4.3 to 0.4.7"))
                            assertTrue(message.contains("Required data migration targets: 0.4.5, 0.4.7."))
                        }
                        null -> {
                            assertTrue(message.contains("needs initialization"))
                            assertTrue(message.contains("Required data migration targets: 0.3.3, 0.3.5"))
                        }
                    }
                    assertFalse(migrationStarted)
                    assertEquals(readOwners, homeLeaseOwners(locks))
                    if (storedVersion == null) {
                        assertFalse(SystemCoroutineFileSystem.exists(versionPath))
                    } else {
                        assertEquals("\"$storedVersion\"", SystemCoroutineFileSystem.readString(versionPath))
                    }
                } finally {
                    reader.closeAndJoin()
                }
                assertTrue(SystemCoroutineFileSystem.list(locks).isEmpty())
                prepareKodexHome(
                    home, MigrationVersion("0.4.11"), KodexHomeMigrations,
                    SystemCoroutineFileSystem,
                ) { _, _ -> migrationStarted = true }.closeAndJoin()
                assertTrue(migrationStarted)
                assertEquals("\"0.4.7\"", SystemCoroutineFileSystem.readString(versionPath))
                assertTrue(SystemCoroutineFileSystem.list(locks).isEmpty())
            }
        }

        test("runs active migrations in order and ignores future entries") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"1.1.0\"")
            val calls = mutableListOf<String>()
            val migrationStarts = mutableListOf<String>()
            val migrations = listOf(
                migration("1.0.0", calls),
                migration("1.2.0", calls),
                migration("2.0.0", calls),
                migration("4.0.0", calls),
            )

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("3.0.0"),
                migrations = migrations,
                fileSystem = SystemCoroutineFileSystem,
                onMigrationStarted = { fromVersion, toVersion ->
                    migrationStarts += "$fromVersion -> $toVersion"
                },
            ).closeAndJoin()

            assertEquals(listOf("1.2.0", "2.0.0"), calls)
            assertEquals(listOf("1.1.0 -> 1.2.0", "1.2.0 -> 2.0.0"), migrationStarts)
            assertEquals("\"2.0.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }

        test("activates the bundled Home skill when upgrading to 0.3.5") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.3.4\"")

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.3.5"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
            ).closeAndJoin()

            assertEquals(
                KodexHomeSkill,
                SystemCoroutineFileSystem.readString(
                    Path(home, "skills", "kodex-home", "SKILL.md"),
                ),
            )
            assertEquals("\"0.3.5\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            assertTrue(SystemCoroutineFileSystem.exists(Path(home, "skills", "kodex-home")))
        }

        test("keeps the last completed version when a migration fails") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"1.0.0\"")
            val migrations = listOf(
                Migration(MigrationVersion("1.1.0")) { _, _ -> },
                Migration(MigrationVersion("1.2.0")) { _, _ -> error("stop") },
            )

            assertFailsWith<IllegalStateException> {
                prepareKodexHome(
                    home = home,
                    currentVersion = MigrationVersion("2.0.0"),
                    migrations = migrations,
                    fileSystem = SystemCoroutineFileSystem,
                )
            }
            assertEquals("\"1.1.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }

        test("rejects newer malformed and unordered versions") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"9.0.0\"")
            assertFailsWith<KodexHomeVersionException> {
                prepareKodexHome(home).closeAndJoin()
            }

            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "{\"version\":\"0.3.2\"}")
            assertFailsWith<KodexHomeVersionException> {
                prepareKodexHome(home).closeAndJoin()
            }

            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"1.0.0\"")
            assertFailsWith<IllegalStateException> {
                prepareKodexHome(
                    home = home,
                    currentVersion = MigrationVersion("3.0.0"),
                    migrations = listOf(
                        migration("2.0.0", mutableListOf()),
                        migration("1.5.0", mutableListOf()),
                    ),
                    fileSystem = SystemCoroutineFileSystem,
                )
            }
        }
    }
}

private fun migration(
    target: String,
    calls: MutableList<String>,
): Migration {
    val toVersion = MigrationVersion(target)
    return Migration(toVersion) { _, _ ->
        calls += toVersion.toString()
    }
}

private suspend fun createEmptySession(home: Path, index: Int): Path {
    val session = Path(home, "sessions", index.toString())
    listOf("index", "work", "settings", "timestamp", "token-count", "unstable")
        .forEach { timelineName ->
            val timeline = Path(session, timelineName)
            SystemCoroutineFileSystem.createDirectories(timeline)
            SystemCoroutineFileSystem.writeString(Path(timeline, "latest.json"), "-1")
        }
    return session
}

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-home-migration-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun homeLeaseOwners(directory: Path): Set<Path> =
    SystemCoroutineFileSystem.list(directory)
        .filter { it.name.endsWith(".read.lock") || it.name.endsWith(".write.lock") }
        .toSet()

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
