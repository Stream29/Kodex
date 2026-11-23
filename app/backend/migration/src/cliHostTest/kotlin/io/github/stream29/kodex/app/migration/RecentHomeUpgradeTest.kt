package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.sqlite.requireKodexHomeDatabase
import io.github.stream29.kodex.app.migration.v0_8_0.RecentHomeSourceDdl
import io.github.stream29.kodex.app.migration.v0_8_0.requireRecentHomeSourceSchema
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.coroutineScope
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val upgradeFs = homeTestFileSystem
private val targetVersion = MigrationVersion("0.8.0")

public val recentHomeUpgradeTest by testSuite {
    testFixture { newTestHome() } closeWith { deleteTestHome(this) } asParameterForEach {
        test("upgrade includes committed WAL data and preserves the privately backed up original") { directory ->
            val home = Path(directory, "home")
            val donor = Path(directory, "wal-source.db")
            val instructions = "A".repeat(160 * 1024)
            upgradeFs.createDirectories(home)
            openSqliteConnection(donor).use { connection ->
                connection.seedRecentSchema()
                assertEquals(listOf(listOf("wal")), connection.query("PRAGMA journal_mode=WAL"))
                connection.execute("PRAGMA wal_autocheckpoint=0")
                connection.execute("INSERT INTO sessions VALUES(12, 1)")
                connection.execute("INSERT INTO agents VALUES(90, 12, NULL, 0)")
                connection.execute(
                    "INSERT INTO agent_commits VALUES(90, 'settings', 0, ?, '2026-11-23T00:00:00Z')",
                    listOf("""{"model":"offline-fixture","threadName":"WAL data","cwd":"/work","instructions":"$instructions"}"""),
                )
                val donorWal = Path(directory, "wal-source.db-wal")
                assertTrue(upgradeFs.exists(donorWal))
                upgradeFs.writeBytes(Path(home, HomeDatabaseFile), upgradeFs.readBytes(donor))
                upgradeFs.writeBytes(Path(home, "$HomeDatabaseFile-wal"), upgradeFs.readBytes(donorWal))
            }
            val marker = Path(home, HomeVersionFile)
            val userFile = Path(home, "user-note")
            upgradeFs.writeString(marker, "\"0.7.5\"")
            upgradeFs.writeString(userFile, "preserve")
            assertFalse(upgradeFs.exists(Path(home, "$HomeDatabaseFile-shm")))

            prepareRecentFixture(home)

            val backup = Path(upgradeAttempt(home), "original.db")
            openExistingSqliteConnection(backup).use {
                requireRecentHomeSourceSchema(it)
                assertEquals(listOf(listOf(12L, 1L)), it.query("SELECT * FROM sessions"))
                assertEquals(listOf(listOf("WAL data")), it.query("SELECT json_extract(payload_json, '$.threadName') FROM agent_commits"))
            }
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use {
                requireKodexHomeDatabase(it)
                assertEquals(listOf(listOf(90L, 1L)), it.query("SELECT agent_id, completed FROM agents"))
                assertEquals(listOf(listOf(90L, "WAL data")), it.query("SELECT root_agent_id, name FROM session_catalog_view"))
                val payload = it.query("SELECT payload_json FROM agent_commits").single().single() as String
                val settings = assertIs<KodexAgentSettings>(AgentCommitType.Settings.decodePayloadJson(payload))
                assertEquals("offline-fixture", settings.model.value)
                assertEquals("WAL data", settings.name)
                assertEquals(instructions, settings.instructions)
            }
            assertEquals(readyMarker("0.8.0"), upgradeFs.readString(marker))
            assertEquals("preserve", upgradeFs.readString(userFile))
            assertFalse(upgradeFs.exists(Path(home, "$HomeDatabaseFile-wal")))
            assertFalse(upgradeFs.exists(Path(home, "$HomeDatabaseFile-shm")))
            val backupBytes = upgradeFs.readBytes(backup)
            val markerBefore = upgradeFs.fingerprintOrNull(marker)
            prepareRecentFixture(home)
            assertEquals(markerBefore, upgradeFs.fingerprintOrNull(marker))
            assertContentEquals(backupBytes, upgradeFs.readBytes(backup))
        }

        for (markerPublished in listOf(false, true)) {
            test("keep a staged backup only if the first upgrade marker was published ($markerPublished)") { home ->
                seedRecentHome(home, "0.7.3")
                val marker = Path(home, HomeVersionFile)
                val original = upgradeFs.readBytes(Path(home, HomeDatabaseFile))
                val failFence = object : CoroutineFileSystem by upgradeFs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (destination == marker && "\"state\":\"upgrading\"" in upgradeFs.readString(source)) {
                            if (markerPublished) upgradeFs.atomicMove(source, destination)
                            throw IOException("interrupted upgrade marker publication")
                        }
                        upgradeFs.atomicMove(source, destination)
                    }
                }
                assertFailsWith<IOException> { prepareRecentFixture(home, failFence) }
                assertContentEquals(original, upgradeFs.readBytes(Path(home, HomeDatabaseFile)))
                if (markerPublished) {
                    val progress = assertIs<HomeFormat.Upgrading>(inspectHome(home, targetVersion, upgradeFs))
                    assertEquals(".kodex-upgrade-0.8.0-${progress.attempt}", upgradeAttempt(home).name)
                } else {
                    assertEquals("\"0.7.3\"", upgradeFs.readString(marker))
                    assertTrue(upgradeFs.list(home).none { it.name.startsWith(".kodex-upgrade-0.8.0-") })
                }
                prepareRecentFixture(home)
                assertEquals(readyMarker("0.8.0"), upgradeFs.readString(marker))
            }
        }

        for (afterPublishingDatabase in listOf(false, true)) {
            test("resume the same attempt after interruption (new database published: $afterPublishingDatabase)") { home ->
                seedRecentHome(home, "0.7.3")
                val marker = Path(home, HomeVersionFile)
                val database = Path(home, HomeDatabaseFile)
                val failPublish = object : CoroutineFileSystem by upgradeFs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        if (
                            !afterPublishingDatabase && destination == database ||
                            afterPublishingDatabase && destination == marker && upgradeFs.readString(source) == readyMarker("0.8.0")
                        ) throw IOException("interrupted release publication")
                        upgradeFs.atomicMove(source, destination)
                    }
                }
                if (afterPublishingDatabase) {
                    assertFailsWith<IOException> { prepareRecentFixture(home, failPublish) }
                } else {
                    val failure = assertFailsWith<KodexHomeVersionException> { prepareRecentFixture(home, failPublish) }
                    assertIs<IOException>(failure.cause)
                }
                val progress = assertIs<HomeFormat.Upgrading>(inspectHome(home, targetVersion, upgradeFs))
                val backupDirectory = upgradeAttempt(home)
                val staging = Path(backupDirectory, "prepared.db")
                assertEquals(!afterPublishingDatabase, upgradeFs.exists(staging))
                assertContains(backupDirectory.name, progress.attempt)
                assertContains(upgradeFs.readString(Path(backupDirectory, "prepared")), "\nsource=0.7.3\n")

                prepareRecentFixture(home)

                assertEquals(readyMarker("0.8.0"), upgradeFs.readString(marker))
                assertEquals(backupDirectory, upgradeAttempt(home))
                assertFalse(upgradeFs.exists(staging))
            }
        }

        test("an unmeasurable response leaves the legacy checkpoint and live bytes intact") { home ->
            seedRecentHome(home, "0.7.5") {
                it.execute("INSERT INTO sessions VALUES(12, 0)")
                it.execute("INSERT INTO agents VALUES(90, 12, NULL, 0)")
                it.execute(
                    """INSERT INTO agent_commits VALUES(90, 'llm_request_meta_response', 0,
                        '{"type":"llm_request_meta_response","total_tokens":3}', '2026-11-23T00:00:00Z')""",
                )
            }
            val database = Path(home, HomeDatabaseFile)
            val before = upgradeFs.readBytes(database)
            val failure = assertFailsWith<KodexHomeVersionException> { prepareRecentFixture(home) }
            assertContains(failure.message.orEmpty(), "agent_id=90")
            assertContains(failure.message.orEmpty(), "index=0")
            assertContains(failure.message.orEmpty(), "usage object")
            assertEquals("\"0.7.5\"", upgradeFs.readString(Path(home, HomeVersionFile)))
            assertContentEquals(before, upgradeFs.readBytes(database))
            assertTrue(upgradeFs.list(home).none { it.name.startsWith(".kodex-upgrade-0.8.0-") })
        }
    }
}

private suspend fun seedRecentHome(home: Path, version: String, seed: (SqliteConnection) -> Unit = {}) {
    openSqliteConnection(Path(home, HomeDatabaseFile)).use {
        it.seedRecentSchema()
        seed(it)
    }
    upgradeFs.writeString(Path(home, HomeVersionFile), "\"$version\"")
}

private fun SqliteConnection.seedRecentSchema() {
    check(query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete")))
    RecentHomeSourceDdl.forEach { execute(it) }
    execute("INSERT INTO sqlite_sequence(name,seq) VALUES('agents',0)")
    execute("PRAGMA user_version=1")
    requireRecentHomeSourceSchema(this)
}

private suspend fun prepareRecentFixture(home: Path, fileSystem: CoroutineFileSystem = upgradeFs) =
    coroutineScope { prepareKodexHome(home, targetVersion, emptyList(), fileSystem).closeAndJoin() }

private suspend fun upgradeAttempt(home: Path) =
    upgradeFs.list(home).single { it.name.startsWith(".kodex-upgrade-0.8.0-") }
