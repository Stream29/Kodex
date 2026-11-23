package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.KodexHomeDatabaseApplicationId
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
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
private val bridgeVersion = MigrationVersion("0.8.0")
private const val Attempt = "018d6421-1093-7ae8-bdc3-8a1fa1aaf881"
private const val OtherAttempt = "018d6421-1093-7ae8-bdc3-8a1fa1aaf882"

public val recentHomeAdmissionTest by testSuite {
    testFixture { newTestHome() } closeWith {
        deleteTestHome(this)
    } asParameterForEach {
        for (source in listOf("0.7.3", "0.7.5")) {
            test("classifies released checkpoint $source without treating it as ready") { home ->
                writeHeaderFixture(home)
                fs.writeString(Path(home, HomeVersionFile), " \n\"$source\"\t")
                fs.writeString(Path(home, "user-note"), "preserve me")
                val before = rootSnapshot(home)
                assertEquals(HomeFormat.RecentLegacy(MigrationVersion(source)), inspectHome(home, bridgeVersion, fs))
                val refused = assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home, fs) }
                assertContains(refused.message.orEmpty(), "kodex backend start")
                assertEquals(before, rootSnapshot(home))
            }
        }

        for (marker in listOf(null, "\"0.7.2\"", "\"0.7.4\"", "\"0.7.6\"", "\"0.8.0\"", "\"00.7.3\"", "\"0.7.\\u0033\"")) {
            test("does not admit a SQLite file with unsupported marker $marker") { home ->
                writeHeaderFixture(home)
                if (marker != null) fs.writeString(Path(home, HomeVersionFile), marker)
                val before = rootSnapshot(home)
                assertFailsWith<KodexHomeVersionException> { preflightKodexHomeStartup(home, fs) }
                assertEquals(before, rootSnapshot(home))
            }
        }

        for ((appId, schema) in listOf(0 to 0, 0 to 2, 173 to 1, KodexHomeDatabaseApplicationId to 2)) {
            test("rejects appId $appId schema $schema under a legacy marker") { home ->
                writeHeaderFixture(home, appId, schema)
                fs.writeString(Path(home, HomeVersionFile), "\"0.7.3\"")
                val before = rootSnapshot(home)
                assertFailsWith<KodexHomeVersionException> { preflightKodexHomeStartup(home, fs) }
                assertEquals(before, rootSnapshot(home))
            }
        }

        test("refuses a directory at an SQLite sidecar path before startup") { home ->
            writeHeaderFixture(home)
            fs.writeString(Path(home, HomeVersionFile), "\"0.7.3\"")
            fs.createDirectories(Path(home, "$HomeDatabaseFile-wal"))
            assertFailsWith<KodexHomeVersionException> { preflightKodexHomeStartup(home, fs) }
            assertTrue(fs.metadataOrNull(Path(home, "$HomeDatabaseFile-wal"))?.isDirectory == true)
            assertFalse(fs.exists(Path(home, ".locks")))
        }

        test("does not reinterpret an initialization staging path as a recent Home") { home ->
            writeHeaderFixture(home)
            fs.writeString(Path(home, HomeVersionFile), "\"0.7.5\"")
            fs.writeString(Path(home, StagingDatabaseFiles.first()), "unrelated staging data")
            val before = rootSnapshot(home)
            assertFailsWith<KodexHomeVersionException> { preflightKodexHomeStartup(home, fs) }
            assertEquals(before, rootSnapshot(home))
        }

        for (vanishes in listOf(true, false)) {
            test("startup tolerates a resolve failure only when the lease entry vanished ($vanishes)") { home ->
                val leases = homeLeaseDirectory(home)
                fs.createDirectories(leases)
                val temporary = Path(leases, ".lock-$Attempt.tmp")
                fs.writeString(temporary, "heartbeat")
                var resolveAttempts = 0
                val duringResolve = object : CoroutineFileSystem by fs {
                    override suspend fun resolve(path: Path): Path {
                        if (path == temporary) {
                            resolveAttempts++
                            if (vanishes) fs.delete(path)
                            throw IllegalStateException()
                        }
                        return fs.resolve(path)
                    }
                }
                if (vanishes) {
                    assertEquals(HomeFormat.Fresh, inspectHome(home, bridgeVersion, duringResolve))
                    assertFalse(fs.exists(temporary))
                } else {
                    assertFailsWith<KodexHomeLayoutException> { inspectHome(home, bridgeVersion, duringResolve) }
                    assertEquals("heartbeat", fs.readString(temporary))
                }
                assertEquals(1, resolveAttempts)
            }
        }

        for ((appId, schema) in listOf(0 to 1, KodexHomeDatabaseApplicationId to 2)) {
            test("classifies the same upgrade attempt before and after the DB publication ($schema)") { home ->
                writeHeaderFixture(home, appId, schema)
                fs.writeString(Path(home, HomeVersionFile), upgradingMarker())
                val before = rootSnapshot(home)
                assertEquals(HomeFormat.Upgrading(MigrationVersion("0.7.3"), Attempt), inspectHome(home, bridgeVersion, fs))
                preflightKodexHomeStartup(home, fs)
                val refused = assertFailsWith<KodexHomeVersionException> { preflightKodexHome(home, fs) }
                assertContains(refused.message.orEmpty(), "resume its upgrade")
                if (schema == 2) requireRecentUpgradeTargetHeader(home, fs)
                else assertFailsWith<KodexHomeVersionException> { requireRecentUpgradeTargetHeader(home, fs) }
                assertEquals(before, rootSnapshot(home))
            }
        }

        for (marker in listOf(
            upgradingMarker(source = "0.7.4"),
            upgradingMarker(target = "0.8.1"),
            upgradingMarker(attempt = ".."),
            upgradingMarker(attempt = "../$Attempt"),
            upgradingMarker(attempt = Attempt.uppercase()),
            upgradingMarker(attempt = Attempt.dropLast(1)),
            upgradingMarker(attempt = "\\u0030${Attempt.drop(1)}"),
            upgradingMarker().replace(",\"attempt\":\"$Attempt\"", ""),
            upgradingMarker().replace("\"attempt\":\"$Attempt\"", "\"attempt\":1"),
            upgradingMarker().replace("\"attempt\":\"$Attempt\"", "\"attempt\":\"$Attempt\",\"attempt\":\"$OtherAttempt\""),
            upgradingMarker().replace("\"attempt\":\"$Attempt\"", "\"attempt\":\"$Attempt\",\"extra\":1"),
            """{"generation":1,"checkpoint":"0.8.0","attempt":"$Attempt"}""",
            """{"generation":1,"checkpoint":"0.8.0","state":"initializing","attempt":"$Attempt"}""",
        )) {
            test("rejects a malformed upgrade envelope: $marker") { home ->
                writeHeaderFixture(home)
                fs.writeString(Path(home, HomeVersionFile), marker)
                val before = rootSnapshot(home)
                assertFailsWith<KodexHomeVersionException> { preflightKodexHomeStartup(home, fs) }
                assertEquals(before, rootSnapshot(home))
            }
        }

        test("an interrupted upgrade marker rename leaves the old marker and data") { home ->
            writeHeaderFixture(home)
            val marker = Path(home, HomeVersionFile)
            fs.writeString(marker, "\"0.7.3\"")
            val before = rootSnapshot(home)
            val failure = IOException("interrupted marker replacement")
            val interrupted = object : CoroutineFileSystem by fs {
                override suspend fun atomicMove(source: Path, destination: Path) {
                    if (destination == marker) throw failure
                    fs.atomicMove(source, destination)
                }
            }
            assertSame(failure, assertFailsWith<IOException> {
                writeUpgradingHomeMarker(home, MigrationVersion("0.7.3"), Attempt, interrupted)
            })
            assertEquals(before, rootSnapshot(home))
            assertFailsWith<IllegalArgumentException> {
                writeUpgradingHomeMarker(home, MigrationVersion("0.7.3"), "../$Attempt", fs)
            }
            assertEquals(before, rootSnapshot(home))
            writeUpgradingHomeMarker(home, MigrationVersion("0.7.3"), Attempt, fs)
            assertEquals(upgradingMarker(), fs.readString(marker))
        }

        test("resume takes only the writer and refuses a different attempt under it") { home ->
            writeHeaderFixture(home)
            val marker = Path(home, HomeVersionFile)
            fs.writeString(marker, upgradingMarker())
            var writes = 0
            val replacedDuringAcquisition = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    check(!path.name.endsWith(".read.lock")) { "An upgrade must not acquire a read lease first" }
                    if (path.name.endsWith(".write.lock")) {
                        writes++
                        fs.writeString(marker, upgradingMarker(attempt = OtherAttempt))
                    }
                    fs.writeString(path, content, append, mustCreate)
                }
            }
            val refused = assertFailsWith<KodexHomeVersionException> {
                prepareKodexHome(home, bridgeVersion, emptyList(), replacedDuringAcquisition) { _, _ ->
                    error("A changed attempt must not start migration")
                }
            }
            assertContains(refused.message.orEmpty(), "upgrade state changed")
            assertEquals(1, writes)
            assertEquals(upgradingMarker(attempt = OtherAttempt), fs.readString(marker))
            assertTrue(fs.list(homeLeaseDirectory(home)).isEmpty())
        }
    }
}

// These are physical-header fixtures, not substitutes for released CLI Home samples.
private fun writeHeaderFixture(home: Path, appId: Int = 0, schema: Int = 1) {
    openSqliteConnection(Path(home, HomeDatabaseFile)).use { connection ->
        connection.execute("CREATE TABLE header_fixture(id INTEGER PRIMARY KEY)")
        connection.execute("PRAGMA user_version=$schema")
        connection.execute("PRAGMA application_id=$appId")
    }
}

private fun upgradingMarker(source: String = "0.7.3", target: String = "0.8.0", attempt: String = Attempt): String =
    """{"generation":1,"checkpoint":"$target","state":"upgrading","source":"$source","attempt":"$attempt"}"""

private suspend fun rootSnapshot(home: Path): Map<String, List<Byte>?> = buildMap {
    for (entry in fs.list(home)) {
        put(entry.name, if (fs.metadataOrNull(entry)?.isDirectory == true) null else fs.readBytes(entry).toList())
    }
}
