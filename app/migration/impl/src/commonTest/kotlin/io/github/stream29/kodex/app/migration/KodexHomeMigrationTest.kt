package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

public val kodexHomeMigrationTest by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("initializes a new Home with the generated application version") { home ->
            val handle = prepareKodexHome(home)
            try {
                assertEquals(MigrationVersion(0, 3, 2), CurrentKodexApplicationVersion)
                assertEquals(
                    "\"$CurrentKodexApplicationVersion\"",
                    SystemCoroutineFileSystem.readString(Path(home, "version.json")),
                )
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

            prepareKodexHome(home).closeAndJoin()
        }

        test("runs active migrations in order and ignores future entries") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"1.1.0\"")
            val calls = mutableListOf<String>()
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
            ).closeAndJoin()

            assertEquals(listOf("1.2.0", "2.0.0"), calls)
            assertEquals("\"3.0.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
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

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
