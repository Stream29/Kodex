package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.v0_3_5.KodexHomeSkill
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

public val kodexHomeMigrationCheckpointTest by testSuite {
    testFixture { checkpointHome() } closeWith {
        deleteCheckpointHome(this)
    } asParameterForEach {
        for (stored in listOf("0.4.7", "0.4.10")) {
            for (applications in listOf(listOf("0.4.10", "0.4.11"), listOf("0.4.11", "0.4.10"))) {
                test("shares checkpoint $stored between applications ${applications.joinToString()}") { home ->
                    val versionPath = Path(home, "version.json")
                    SystemCoroutineFileSystem.writeString(versionPath, "\"$stored\"")
                    // A compatible, versioned Home must not scan the Session layout.
                    SystemCoroutineFileSystem.writeString(Path(home, "sessions"), "not-a-directory")
                    val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                        override suspend fun writeString(
                            path: Path,
                            content: String,
                            append: Boolean,
                            mustCreate: Boolean,
                        ) {
                            check(path != versionPath && !path.name.endsWith(".write.lock")) {
                                "Compatible Home must not acquire a writer or rewrite its checkpoint: $path"
                            }
                            SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                        }
                    }
                    val migrations = KodexHomeMigrations +
                        Migration(MigrationVersion("99.0.0")) { _, _ -> error("Future migration ran") }
                    var migrationStarted = false
                    val first = prepareKodexHome(
                        home, MigrationVersion(applications[0]), migrations, fileSystem,
                    ) { _, _ -> migrationStarted = true }
                    try {
                        val second = prepareKodexHome(
                            home, MigrationVersion(applications[1]), migrations, fileSystem,
                        ) { _, _ -> migrationStarted = true }
                        try {
                            assertEquals(MigrationVersion(stored), first.version)
                            assertEquals(MigrationVersion(stored), second.version)
                            assertEquals("\"$stored\"", SystemCoroutineFileSystem.readString(versionPath))
                            assertFalse(migrationStarted)
                            first.closeAndJoin()
                            assertEquals(
                                1,
                                SystemCoroutineFileSystem.list(Path(home, ".locks", "home"))
                                    .count { it.name.endsWith(".read.lock") },
                            )
                        } finally {
                            second.closeAndJoin()
                        }
                    } finally {
                        first.closeAndJoin()
                    }
                    assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())
                }
            }
        }

        test("registers only the validated baseline when no migration is activated") { home ->
            val future = listOf(Migration(MigrationVersion("2.0.0")) { _, _ -> error("Future migration ran") })
            val handle = prepareKodexHome(home, MigrationVersion("1.0.0"), future, SystemCoroutineFileSystem)
            try {
                assertEquals(MigrationVersion("0.3.2"), handle.version)
                assertEquals("\"0.3.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                assertFalse(SystemCoroutineFileSystem.exists(Path(home, "sessions")))
            } finally {
                handle.closeAndJoin()
            }
            prepareKodexHome(home, MigrationVersion("1.1.0"), future, SystemCoroutineFileSystem).closeAndJoin()
            assertEquals("\"0.3.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())
        }

        test("0.4.2 activates only the 0.3.5 skill and retains that migration checkpoint") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.3.4\"")
            val activated = mutableListOf<MigrationVersion>()
            val handle = prepareKodexHome(
                home, MigrationVersion("0.4.2"), KodexHomeMigrations, SystemCoroutineFileSystem,
            ) { _, target -> activated += target }
            try {
                assertEquals(listOf(MigrationVersion("0.3.5")), activated)
                assertEquals(MigrationVersion("0.3.5"), handle.version)
                assertEquals(
                    KodexHomeSkill,
                    SystemCoroutineFileSystem.readString(Path(home, "skills", "kodex-home", "SKILL.md")),
                )
                assertEquals("\"0.3.5\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            } finally {
                handle.closeAndJoin()
            }
            assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())
        }

        test("retains the baseline checkpoint when the first migration fails and retries its partial output") { home ->
            val primary = IOException("first migration interrupted")
            var attempts = 0
            val migrations = listOf(
                Migration(MigrationVersion("0.3.3")) { target, fileSystem ->
                    attempts += 1
                    fileSystem.createDirectories(Path(target, "sessions", "0"))
                    if (attempts == 1) throw primary
                    // This incomplete Session would fail baseline validation on a second unversioned start.
                    fileSystem.writeString(Path(target, "migration-result.txt"), "completed")
                },
            )
            assertSame(
                primary,
                assertFailsWith<IOException> {
                    prepareKodexHome(home, MigrationVersion("0.3.4"), migrations, SystemCoroutineFileSystem)
                },
            )
            assertEquals("\"0.3.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())

            val handle = prepareKodexHome(home, MigrationVersion("0.3.4"), migrations, SystemCoroutineFileSystem)
            try {
                assertEquals(MigrationVersion("0.3.3"), handle.version)
                assertEquals(2, attempts)
                assertEquals("\"0.3.3\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                assertEquals("completed", SystemCoroutineFileSystem.readString(Path(home, "migration-result.txt")))
            } finally {
                handle.closeAndJoin()
            }
        }

        test("rereads the checkpoint under the writer and skips a migration completed before acquisition") { home ->
            val versionPath = Path(home, "version.json")
            SystemCoroutineFileSystem.writeString(versionPath, "\"1.0.0\"")
            val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writeString(
                    path: Path,
                    content: String,
                    append: Boolean,
                    mustCreate: Boolean,
                ) {
                    SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                    if (path.name.endsWith(".write.lock")) {
                        SystemCoroutineFileSystem.writeString(versionPath, "\"2.0.0\"")
                    }
                }
            }
            var actions = 0
            var callbacks = 0
            val handle = prepareKodexHome(
                home, MigrationVersion("3.0.0"),
                listOf(Migration(MigrationVersion("2.0.0")) { _, _ -> actions += 1 }),
                fileSystem,
            ) { _, _ -> callbacks += 1 }
            try {
                assertEquals(0, actions)
                assertEquals(0, callbacks)
                assertEquals(MigrationVersion("2.0.0"), handle.version)
                assertEquals("\"2.0.0\"", SystemCoroutineFileSystem.readString(versionPath))
            } finally {
                handle.closeAndJoin()
            }
        }

        for (handoffVersion in listOf("1.2.0", "1.0.0", "4.0.0", null)) {
            test("rechecks checkpoint ${handoffVersion ?: "missing"} after writer handoff") { home ->
                val versionPath = Path(home, "version.json")
                SystemCoroutineFileSystem.writeString(versionPath, "\"1.0.0\"")
                val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        SystemCoroutineFileSystem.delete(path, mustExist)
                        if (path.name.endsWith(".write.lock")) {
                            if (handoffVersion == null) {
                                SystemCoroutineFileSystem.delete(versionPath)
                            } else {
                                SystemCoroutineFileSystem.writeString(versionPath, "\"$handoffVersion\"")
                            }
                        }
                    }
                }
                val migrations = listOf(Migration(MigrationVersion("1.1.0")) { _, _ -> })
                if (handoffVersion == "1.2.0") {
                    val handle = prepareKodexHome(home, MigrationVersion("3.0.0"), migrations, fileSystem)
                    try {
                        assertEquals(MigrationVersion(handoffVersion), handle.version)
                    } finally {
                        handle.closeAndJoin()
                    }
                } else {
                    assertFailsWith<KodexHomeVersionException> {
                        prepareKodexHome(home, MigrationVersion("3.0.0"), migrations, fileSystem)
                    }
                }
                assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())
            }
        }
    }
}

private suspend fun checkpointHome(): Path =
    Path(SystemTemporaryDirectory, "kodex-home-checkpoint-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteCheckpointHome(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteCheckpointHome(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
