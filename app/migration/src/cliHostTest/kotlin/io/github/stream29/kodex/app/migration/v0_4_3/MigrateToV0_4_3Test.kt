package io.github.stream29.kodex.app.migration.v0_4_3

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_3_5.KodexHomeSkill as PreviousKodexHomeSkill
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

public val migrateToV0_4_3Test by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.4.3-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("replaces only the product skill and cleans only its own temporary") { home ->
            seedPreviousHome(home)
            val preserved = mapOf(
                "settings.yml" to "shell: bash\n",
                "auth.yml" to "fixture-only-auth",
                "AGENTS.md" to "user instructions",
                "log/keep.log" to "log",
                "generated_images/keep.png" to "image",
                "skills/other/SKILL.md" to "other skill",
                "skills/kodex-home/README.md" to "sibling",
                "skills/kodex-home/unknown.tmp" to "unknown temporary",
                "skills/kodex-home/.kodex-migration-0.3.5-skill.tmp" to "other owner",
                "sessions/0/index/0.json" to """{"type":"compaction_point"}""",
                "sessions/0/index/latest.json" to "0",
                "sessions/0/work/latest.json" to "-1",
                "sessions/0/settings/0.json" to """{"model":"fixture","cwd":"/fixture"}""",
                "sessions/0/settings/latest.json" to "0",
                "sessions/0/timestamp/0.json" to "\"2026-09-07T00:00:00Z\"",
                "sessions/0/timestamp/latest.json" to "0",
                "sessions/0/token-count/0.json" to "0",
                "sessions/0/token-count/latest.json" to "0",
                "sessions/0/unstable/latest.json" to "-1",
                "sessions/0/archive.mark" to "",
                "sessions/0/subagents/legacy.json" to "legacy",
                "unknown/user.data" to "unknown",
            )
            preserved.forEach { (relativePath, content) ->
                val path = Path(home, relativePath)
                SystemCoroutineFileSystem.createDirectories(checkNotNull(path.parent))
                SystemCoroutineFileSystem.writeString(path, content)
            }
            SystemCoroutineFileSystem.writeString(temporaryPath(home), "interrupted write")

            migrateToV0_4_3(home, SystemCoroutineFileSystem)

            assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(skillPath(home)))
            preserved.forEach { (relativePath, content) ->
                assertEquals(content, SystemCoroutineFileSystem.readString(Path(home, relativePath)))
            }
            assertFalse(SystemCoroutineFileSystem.exists(temporaryPath(home)))
            assertEquals("\"0.4.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }

        test("replaces a locally edited skill and remains repeatable") { home ->
            seedPreviousHome(home)
            SystemCoroutineFileSystem.writeString(skillPath(home), "locally edited product skill")

            repeat(2) {
                migrateToV0_4_3(home, SystemCoroutineFileSystem)
                assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(skillPath(home)))
                assertFalse(SystemCoroutineFileSystem.exists(temporaryPath(home)))
            }
        }

        test("does not activate the future skill at application version 0.4.2") { home ->
            seedPreviousHome(home, "0.3.4")
            val activated = mutableListOf<MigrationVersion>()

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.4.2"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
                onMigrationStarted = { _, target -> activated += target },
            ).closeAndJoin()

            assertEquals(listOf(MigrationVersion("0.3.5")), activated)
            assertEquals(PreviousKodexHomeSkill, SystemCoroutineFileSystem.readString(skillPath(home)))
            assertEquals("\"0.4.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }

        for (previousVersion in listOf(null, "0.3.2", "0.3.4", "0.3.5", "0.4.2")) {
            test("prepares an isolated Home from ${previousVersion ?: "unversioned"} to 0.4.3") { home ->
                if (previousVersion != null) seedPreviousHome(home, previousVersion)
                val activated = mutableListOf<MigrationVersion>()

                prepareKodexHome(
                    home = home,
                    currentVersion = MigrationVersion("0.4.3"),
                    migrations = KodexHomeMigrations,
                    fileSystem = SystemCoroutineFileSystem,
                    onMigrationStarted = { _, target -> activated += target },
                ).closeAndJoin()

                val expected = listOf("0.3.3", "0.3.5", "0.4.3").map(::MigrationVersion)
                    .filter { previousVersion == null || it > MigrationVersion(previousVersion) }
                assertEquals(expected, activated)
                assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(skillPath(home)))
                assertEquals("\"0.4.3\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                assertFalse(SystemCoroutineFileSystem.exists(Path(home, "sessions")))
                assertFalse(SystemCoroutineFileSystem.exists(temporaryPath(home)))
            }
        }

        test("upgrades without enumerating Session history") { home ->
            seedPreviousHome(home)
            val sessions = Path(home, "sessions")
            SystemCoroutineFileSystem.createDirectories(Path(sessions, "0"))
            val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun list(directory: Path): Collection<Path> {
                    check(directory != sessions) { "Skill migration must not scan Sessions." }
                    return SystemCoroutineFileSystem.list(directory)
                }
            }

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.4.3"),
                migrations = KodexHomeMigrations,
                fileSystem = fileSystem,
            ).closeAndJoin()

            assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(skillPath(home)))
        }

        for (publishFirst in listOf(false, true)) {
            for (cancelled in listOf(false, true)) {
                val boundary = if (publishFirst) "after" else "before"
                val interruption = if (cancelled) "cancellation" else "I/O failure"
                test("reruns after $interruption $boundary atomic publication") { home ->
                    seedPreviousHome(home)
                    val target = skillPath(home)
                    val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                        override suspend fun atomicMove(source: Path, destination: Path) {
                            if (destination == target) {
                                assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(source))
                                assertEquals(PreviousKodexHomeSkill, SystemCoroutineFileSystem.readString(target))
                                if (publishFirst) SystemCoroutineFileSystem.atomicMove(source, destination)
                                if (cancelled) {
                                    currentCoroutineContext().cancel(
                                        CancellationException("injected migration cancellation"),
                                    )
                                    currentCoroutineContext().ensureActive()
                                }
                                throw IOException("injected migration failure")
                            }
                            SystemCoroutineFileSystem.atomicMove(source, destination)
                        }
                    }
                    val attempt: suspend () -> Unit = {
                        coroutineScope {
                            prepareKodexHome(
                                home = home,
                                currentVersion = MigrationVersion("0.4.3"),
                                migrations = KodexHomeMigrations,
                                fileSystem = fileSystem,
                            ).closeAndJoin()
                        }
                    }

                    if (cancelled) {
                        assertFailsWith<CancellationException> { attempt() }
                    } else {
                        assertFailsWith<IOException> { attempt() }
                    }
                    assertEquals(
                        if (publishFirst) KodexHomeSkill else PreviousKodexHomeSkill,
                        SystemCoroutineFileSystem.readString(target),
                    )
                    assertEquals("\"0.4.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                    assertFalse(SystemCoroutineFileSystem.exists(temporaryPath(home)))

                    prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("0.4.3"),
                        migrations = KodexHomeMigrations,
                        fileSystem = SystemCoroutineFileSystem,
                    ).closeAndJoin()

                    assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(target))
                    assertEquals("\"0.4.3\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                }
            }
        }

        test("preserves conflicting user data and does not advance the version") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.2\"")
            SystemCoroutineFileSystem.writeString(Path(home, "skills"), "not a directory")

            assertFailsWith<IOException> {
                prepareKodexHome(
                    home = home,
                    currentVersion = MigrationVersion("0.4.3"),
                    migrations = KodexHomeMigrations,
                    fileSystem = SystemCoroutineFileSystem,
                ).closeAndJoin()
            }

            assertEquals("not a directory", SystemCoroutineFileSystem.readString(Path(home, "skills")))
            assertEquals("\"0.4.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }
    }
}

private fun skillPath(home: Path): Path = Path(home, "skills", "kodex-home", "SKILL.md")

private fun temporaryPath(home: Path): Path =
    Path(home, "skills", "kodex-home", ".kodex-migration-0.4.3-skill.tmp")

private suspend fun seedPreviousHome(home: Path, version: String = "0.4.2") {
    val skill = skillPath(home)
    SystemCoroutineFileSystem.createDirectories(checkNotNull(skill.parent))
    SystemCoroutineFileSystem.writeString(skill, PreviousKodexHomeSkill)
    SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"$version\"")
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
