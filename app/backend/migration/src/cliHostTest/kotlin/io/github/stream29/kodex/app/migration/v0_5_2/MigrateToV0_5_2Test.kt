package io.github.stream29.kodex.app.migration.v0_5_2

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

public val migrateToV0_5_2Test by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.5.2-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        val home = this
        withContext(NonCancellable) {
            withTimeout(10.seconds) { deleteRecursively(home) }
        }
    } asParameterForEach {
        test("registers 0.5.2 as the strictly increasing migration target after 0.4.15") {
            assertEquals(
                listOf(
                    MigrationVersion("0.3.3"),
                    MigrationVersion("0.3.5"),
                    MigrationVersion("0.4.3"),
                    MigrationVersion("0.4.5"),
                    MigrationVersion("0.4.7"),
                    MigrationVersion("0.4.15"),
                    MigrationVersion("0.5.2"),
                ),
                KodexHomeMigrations
                    .filter { migration -> migration.toVersion <= MigrationVersion("0.5.2") }
                    .map { migration -> migration.toVersion },
            )
        }

        test("preserves unknown action and argument fields under an explicit command discriminator") { home ->
            val session = createFullSessionSkeleton(home, 0)
            val actions = listOf(
                """{"type":"exec_command","extra":{"keep":true},"arguments":{"cmd":"ls","session_id":7,"chars":"metadata"}}""" to 10_000,
                """{"type":"write_stdin","extra":{"keep":true},"arguments":{"session_id":7,"cmd":"metadata","workdir":"/unused"}}""" to 250,
            )
            for ((index, fixture) in actions.withIndex()) {
                val (action, yieldMillis) = fixture
                val migratedAction = action.dropLast(2) + ""","yield_time_ms":$yieldMillis}}"""
                val work = recordPath(timelineDirectory(session, "work"), index)
                val unstable = recordPath(timelineDirectory(session, "unstable"), index)
                val workPrefix = """{"type":"command_execution_tool_event","call_id":"work-$index","action":"""
                val workSuffix = ""","result":{"type":"failure","message":"historical failure"}}"""
                val unstablePrefix = """[{"type":"command_execution","call_id":"pending-$index","action":"""
                SystemCoroutineFileSystem.writeString(work, "$workPrefix$action$workSuffix")
                SystemCoroutineFileSystem.writeString(unstable, "$unstablePrefix$action}]")

                migrateToV0_5_2(home, SystemCoroutineFileSystem)

                assertEquals("$workPrefix$migratedAction$workSuffix", SystemCoroutineFileSystem.readString(work))
                assertEquals("$unstablePrefix$migratedAction}]", SystemCoroutineFileSystem.readString(unstable))
            }
        }

        test("handles empty Home and empty or missing timelines without creating directories or rewriting empty arrays") { home ->
            migrateToV0_5_2(home, SystemCoroutineFileSystem)
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "sessions")))

            val emptySession = Path(home, "sessions", "0")
            val work = timelineDirectory(emptySession, "work")
            val unstable = timelineDirectory(emptySession, "unstable")
            SystemCoroutineFileSystem.createDirectories(work)
            SystemCoroutineFileSystem.createDirectories(unstable)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "-1")
            SystemCoroutineFileSystem.writeString(Path(unstable, "latest.json"), "0")
            val emptyArray = "[\n]\n"
            SystemCoroutineFileSystem.writeString(recordPath(unstable, 0), emptyArray)
            // Session 1 has no work/unstable directories yet.
            SystemCoroutineFileSystem.createDirectories(Path(home, "sessions", "1"))

            val writtenPaths = mutableListOf<Path>()
            val trackingFs = WriteTrackingFileSystem(writtenPaths)
            migrateToV0_5_2(home, trackingFs)

            assertTrue(writtenPaths.isEmpty())
            assertEquals(emptyArray, SystemCoroutineFileSystem.readString(recordPath(unstable, 0)))
            assertEquals("-1", SystemCoroutineFileSystem.readString(Path(work, "latest.json")))
            assertEquals("0", SystemCoroutineFileSystem.readString(Path(unstable, "latest.json")))
        }

        test("migrates missing command yields across active and archived root sessions while preserving existing values, unknown fields, and unrelated files") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")

            // Active root session 0
            val session0 = createFullSessionSkeleton(home, 0)
            val work0 = timelineDirectory(session0, "work")
            val unstable0 = timelineDirectory(session0, "unstable")
            SystemCoroutineFileSystem.writeString(recordPath(work0, 0), LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 1), LegacyWorkWriteEmptyCharsMissingYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 2), PrettyWorkExecNegativeYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 3), WorkWriteOutOfRangeYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 4), UnrelatedWorkTextToolEvent)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 5), WorkInvalidRawExecToolCall)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 6), UnknownFutureWorkEvent)
            SystemCoroutineFileSystem.writeString(Path(work0, "latest.json"), "6")

            SystemCoroutineFileSystem.writeString(recordPath(unstable0, 0), "[]")
            SystemCoroutineFileSystem.writeString(recordPath(unstable0, 1), LegacyUnstableMultiEventArray)
            SystemCoroutineFileSystem.writeString(recordPath(unstable0, 2), PrettyUnstableAlreadyMigratedArray)
            SystemCoroutineFileSystem.writeString(Path(unstable0, "latest.json"), "2")

            // Archived root session 2 (non-contiguous index with archive.mark)
            val session2 = createFullSessionSkeleton(home, 2, archived = true)
            val work2 = timelineDirectory(session2, "work")
            val unstable2 = timelineDirectory(session2, "unstable")
            SystemCoroutineFileSystem.writeString(recordPath(work2, 0), LegacyWorkWriteNonEmptyCharsMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work2, "latest.json"), "0")
            SystemCoroutineFileSystem.writeString(
                recordPath(unstable2, 0),
                """[{"type":"command_execution","call_id":"arch-exec","action":{"type":"exec_command","arguments":{"cmd":"ls -la"}}}]""",
            )
            SystemCoroutineFileSystem.writeString(Path(unstable2, "latest.json"), "0")

            // Legacy subagents directory inside session 0 must NOT be traversed or modified
            val legacySubagentWork = Path(session0, "subagents", "0", "work", "0.json")
            SystemCoroutineFileSystem.createDirectories(checkNotNull(legacySubagentWork.parent))
            SystemCoroutineFileSystem.writeString(legacySubagentWork, LegacyWorkExecMissingYield)

            val unknownRootFile = Path(home, "user-notes.txt")
            val unknownTimelineFile = Path(work0, "custom.note")
            SystemCoroutineFileSystem.writeString(unknownRootFile, "root-keep")
            SystemCoroutineFileSystem.writeString(unknownTimelineFile, "timeline-keep")

            val writtenPaths = mutableListOf<Path>()
            val trackingFs = WriteTrackingFileSystem(writtenPaths)

            migrateToV0_5_2(home, trackingFs)

            // Verify only the records missing yield_time_ms were written
            assertEquals(
                setOf(
                    recordPath(work0, 0),
                    recordPath(work0, 1),
                    recordPath(unstable0, 1),
                    recordPath(work2, 0),
                    recordPath(unstable2, 0),
                ),
                writtenPaths.toSet(),
            )
            assertEquals(5, writtenPaths.size)

            // Verify migrated payloads
            assertEquals(MigratedWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work0, 0)))
            assertEquals(MigratedWorkWriteEmptyCharsMissingYield, SystemCoroutineFileSystem.readString(recordPath(work0, 1)))
            assertEquals(MigratedUnstableMultiEventArray, SystemCoroutineFileSystem.readString(recordPath(unstable0, 1)))
            assertEquals(MigratedWorkWriteNonEmptyCharsMissingYield, SystemCoroutineFileSystem.readString(recordPath(work2, 0)))
            assertEquals(
                """[{"type":"command_execution","call_id":"arch-exec","action":{"type":"exec_command","arguments":{"cmd":"ls -la","yield_time_ms":10000}}}]""",
                SystemCoroutineFileSystem.readString(recordPath(unstable2, 0)),
            )

            // Verify byte-for-byte preservation of already-complete, negative, out-of-range, and unrelated records
            assertContentEquals(
                PrettyWorkExecNegativeYield.encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(work0, 2)),
            )
            assertContentEquals(
                WorkWriteOutOfRangeYield.encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(work0, 3)),
            )
            assertContentEquals(
                UnrelatedWorkTextToolEvent.encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(work0, 4)),
            )
            assertContentEquals(
                WorkInvalidRawExecToolCall.encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(work0, 5)),
            )
            assertContentEquals(
                UnknownFutureWorkEvent.encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(work0, 6)),
            )
            assertContentEquals(
                "[]".encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(unstable0, 0)),
            )
            assertContentEquals(
                PrettyUnstableAlreadyMigratedArray.encodeToByteArray(),
                SystemCoroutineFileSystem.readBytes(recordPath(unstable0, 2)),
            )

            // Verify pointers, other timelines, archive marker, legacy subagents, and unknown files are untouched
            assertEquals("6", SystemCoroutineFileSystem.readString(Path(work0, "latest.json")))
            assertEquals("2", SystemCoroutineFileSystem.readString(Path(unstable0, "latest.json")))
            assertEquals("0", SystemCoroutineFileSystem.readString(Path(work2, "latest.json")))
            assertEquals("0", SystemCoroutineFileSystem.readString(Path(unstable2, "latest.json")))
            assertEquals("""{"type":"compaction_point"}""", SystemCoroutineFileSystem.readString(Path(session0, "index", "0.json")))
            assertEquals("\"2026-10-20T00:00:00Z\"", SystemCoroutineFileSystem.readString(Path(session0, "timestamp", "0.json")))
            assertTrue(SystemCoroutineFileSystem.exists(Path(session2, "archive.mark")))
            assertEquals(LegacyWorkExecMissingYield, SystemCoroutineFileSystem.readString(legacySubagentWork))
            assertEquals("root-keep", SystemCoroutineFileSystem.readString(unknownRootFile))
            assertEquals("timeline-keep", SystemCoroutineFileSystem.readString(unknownTimelineFile))

            // Re-running must be a complete no-op with zero file writes
            val secondRunWrites = mutableListOf<Path>()
            migrateToV0_5_2(home, WriteTrackingFileSystem(secondRunWrites))
            assertTrue(secondRunWrites.isEmpty())
        }

        test("resumes after partial file failure without rewriting already migrated records and advances checkpoint only on completion") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
            val session = createFullSessionSkeleton(home, 0)
            val work = timelineDirectory(session, "work")
            val unstable = timelineDirectory(session, "unstable")
            SystemCoroutineFileSystem.writeString(recordPath(work, 0), LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(recordPath(work, 1), LegacyWorkWriteEmptyCharsMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "1")
            SystemCoroutineFileSystem.writeString(recordPath(unstable, 0), LegacyUnstableMultiEventArray)
            SystemCoroutineFileSystem.writeString(Path(unstable, "latest.json"), "0")

            val injectedFailure = IOException("injected write failure on second work record")
            val failingFs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writePrivateBytes(
                    path: Path,
                    content: ByteArray,
                    mustCreate: Boolean,
                ) {
                    if (path == migrationTemporary(work) &&
                        SystemCoroutineFileSystem.readString(recordPath(work, 0)) == MigratedWorkExecMissingYield
                    ) {
                        throw injectedFailure
                    }
                    SystemCoroutineFileSystem.writePrivateBytes(path, content, mustCreate)
                }
            }

            assertSame(
                injectedFailure,
                assertFailsWith<IOException> {
                    prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("0.5.2"),
                        migrations = KodexHomeMigrations,
                        fileSystem = failingFs,
                    ).closeAndJoin()
                },
            )

            // Only the first record was atomically published; the checkpoint remains at 0.4.15.
            assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            assertEquals(MigratedWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 0)))
            assertEquals(LegacyWorkWriteEmptyCharsMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 1)))
            assertEquals(LegacyUnstableMultiEventArray, SystemCoroutineFileSystem.readString(recordPath(unstable, 0)))

            val retryRecordWrites = mutableListOf<Path>()
            val retryFs = WriteTrackingFileSystem(
                writtenPaths = retryRecordWrites,
                trackOnlySessionRecords = true,
            )
            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.2"),
                migrations = KodexHomeMigrations,
                fileSystem = retryFs,
            ).closeAndJoin()

            assertEquals(
                listOf(recordPath(work, 1), recordPath(unstable, 0)),
                retryRecordWrites,
            )
            assertEquals(MigratedWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 0)))
            assertEquals(MigratedWorkWriteEmptyCharsMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 1)))
            assertEquals(MigratedUnstableMultiEventArray, SystemCoroutineFileSystem.readString(recordPath(unstable, 0)))
            assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }

        test("propagates coroutine cancellation without advancing checkpoint and completes on retry") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
            val session = createFullSessionSkeleton(home, 0)
            val work = timelineDirectory(session, "work")
            SystemCoroutineFileSystem.writeString(recordPath(work, 0), LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "0")

            val cancellingFs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writePrivateBytes(
                    path: Path,
                    content: ByteArray,
                    mustCreate: Boolean,
                ) {
                    if (path == migrationTemporary(work)) {
                        SystemCoroutineFileSystem.writePrivateBytes(path, content.copyOf(7), mustCreate)
                        currentCoroutineContext().cancel(CancellationException("cancelled during 0.5.2 write"))
                        currentCoroutineContext().ensureActive()
                    }
                    SystemCoroutineFileSystem.writePrivateBytes(path, content, mustCreate)
                }
            }

            assertFailsWith<CancellationException> {
                coroutineScope {
                    prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("0.5.2"),
                        migrations = KodexHomeMigrations,
                        fileSystem = cancellingFs,
                    ).closeAndJoin()
                }
            }

            assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            assertEquals(LegacyWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 0)))
            assertFalse(SystemCoroutineFileSystem.exists(migrationTemporary(work)))
            assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.2"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
            ).closeAndJoin()

            assertEquals(MigratedWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 0)))
            assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
        }

        for (timelineName in listOf("work", "unstable")) {
            for (fault in listOf("partial write", "before publish", "after publish")) {
                test("0.6.0 preserves $timelineName records across $fault and retries safely") { home ->
                    val version = Path(home, "version.json")
                    SystemCoroutineFileSystem.writeString(version, "\"0.4.15\"")
                    val session = createFullSessionSkeleton(home, 0)
                    val timeline = timelineDirectory(session, timelineName)
                    val target = recordPath(timeline, 0)
                    val temporary = migrationTemporary(timeline)
                    val original = if (timelineName == "work") LegacyWorkExecMissingYield else LegacyUnstableMultiEventArray
                    val expected = if (timelineName == "work") MigratedWorkExecMissingYield else MigratedUnstableMultiEventArray
                    SystemCoroutineFileSystem.writeString(target, original)
                    SystemCoroutineFileSystem.writeString(Path(timeline, "latest.json"), "0")
                    // Simulate an abandoned staging file from a killed prior process.
                    SystemCoroutineFileSystem.writeString(temporary, "{stale-partial")
                    val injected = IOException("injected $fault")
                    val failing = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                        override suspend fun writePrivateBytes(
                            path: Path,
                            content: ByteArray,
                            mustCreate: Boolean,
                        ) {
                            if (path == temporary && fault == "partial write") {
                                SystemCoroutineFileSystem.writePrivateBytes(path, content.copyOf(7), mustCreate)
                                throw injected
                            }
                            SystemCoroutineFileSystem.writePrivateBytes(path, content, mustCreate)
                        }

                        override suspend fun atomicMove(source: Path, destination: Path) {
                            if (source == temporary && fault == "before publish") throw injected
                            SystemCoroutineFileSystem.atomicMove(source, destination)
                            if (source == temporary && fault == "after publish") throw injected
                        }
                    }
                    assertSame(injected, assertFailsWith<IOException> {
                        prepareKodexHome(
                            home, MigrationVersion("0.6.0"), KodexHomeMigrations, failing,
                        ).closeAndJoin()
                    })
                    assertEquals(if (fault == "after publish") expected else original, SystemCoroutineFileSystem.readString(target))
                    assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(version))
                    assertFalse(SystemCoroutineFileSystem.exists(temporary))
                    prepareKodexHome(
                        home, MigrationVersion("0.6.0"), KodexHomeMigrations, SystemCoroutineFileSystem,
                    ).closeAndJoin()
                    assertEquals(expected, SystemCoroutineFileSystem.readString(target))
                    assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(version))
                    assertFalse(SystemCoroutineFileSystem.exists(temporary))
                }
            }
        }

        test("cancellation after atomic publication retains a whole record and retries without rewriting it") { home ->
            val version = Path(home, "version.json")
            SystemCoroutineFileSystem.writeString(version, "\"0.4.15\"")
            val session = createFullSessionSkeleton(home, 0)
            val work = timelineDirectory(session, "work")
            val target = recordPath(work, 0)
            val temporary = migrationTemporary(work)
            SystemCoroutineFileSystem.writeString(target, LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "0")
            val cancelling = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun atomicMove(source: Path, destination: Path) {
                    SystemCoroutineFileSystem.atomicMove(source, destination)
                    if (source == temporary) {
                        currentCoroutineContext().cancel(CancellationException("cancelled after publish"))
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            assertFailsWith<CancellationException> {
                coroutineScope {
                    prepareKodexHome(
                        home, MigrationVersion("0.6.0"), KodexHomeMigrations, cancelling,
                    ).closeAndJoin()
                }
            }
            assertEquals(MigratedWorkExecMissingYield, SystemCoroutineFileSystem.readString(target))
            assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(version))
            assertFalse(SystemCoroutineFileSystem.exists(temporary))
            val rewritten = mutableListOf<Path>()
            prepareKodexHome(
                home, MigrationVersion("0.6.0"), KodexHomeMigrations,
                WriteTrackingFileSystem(rewritten, trackOnlySessionRecords = true),
            ).closeAndJoin()
            assertTrue(rewritten.isEmpty())
            assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(version))
        }

        test("0.6.0 upgrades published and legacy checkpoints and keeps restart read-only") { home ->
            for (checkpoint in listOf("0.4.15", "0.5.1", "0.5.2")) {
                val root = Path(home, checkpoint)
                SystemCoroutineFileSystem.createDirectories(root)
                val version = Path(root, "version.json")
                SystemCoroutineFileSystem.writeString(version, "\"$checkpoint\"")
                prepareKodexHome(
                    root, MigrationVersion("0.6.0"), KodexHomeMigrations, SystemCoroutineFileSystem,
                ).closeAndJoin()
                assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(version))
                SystemCoroutineFileSystem.writeString(Path(root, "sessions"), "must not scan")
                val guarded = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun writeBytes(
                        path: Path,
                        content: ByteArray,
                        append: Boolean,
                        mustCreate: Boolean,
                    ) {
                        check(path != version && !path.name.endsWith(".write.lock"))
                        SystemCoroutineFileSystem.writeBytes(path, content, append, mustCreate)
                    }
                }
                val handle = prepareKodexHome(
                    root, MigrationVersion("0.6.0"), KodexHomeMigrations, guarded,
                )
                try {
                    assertEquals(MigrationVersion("0.5.2"), handle.version)
                } finally {
                    handle.closeAndJoin()
                }
                assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(version))
            }
        }

        test("migrates a Native scale fixture and performs no record writes on the second pass") { home ->
            val session = createFullSessionSkeleton(home, 0)
            val work = timelineDirectory(session, "work")
            val count = 512
            repeat(count) { index ->
                SystemCoroutineFileSystem.writeString(recordPath(work, index), LegacyWorkExecMissingYield)
            }
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), (count - 1).toString())
            val started = TimeSource.Monotonic.markNow()
            migrateToV0_5_2(home, SystemCoroutineFileSystem)
            repeat(count) { index ->
                assertEquals(MigratedWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, index)))
            }
            val writes = mutableListOf<Path>()
            migrateToV0_5_2(home, WriteTrackingFileSystem(writes))
            assertTrue(writes.isEmpty())
            assertFalse(SystemCoroutineFileSystem.exists(migrationTemporary(work)))
            println("0.5.2 Native fixture: records=$count sourceBytes=${count * LegacyWorkExecMissingYield.encodeToByteArray().size} migrationAndReadback=${started.elapsedNow()}")
        }

        for ((label, invalidWorkPayload) in invalidWorkPayloads()) {
            test("rejects $label in work timeline with pathful IOException and retains checkpoint") { home ->
                SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
                val session = createFullSessionSkeleton(home, 0)
                val work = timelineDirectory(session, "work")
                val targetRecord = recordPath(work, 0)
                SystemCoroutineFileSystem.writeString(targetRecord, invalidWorkPayload)
                SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "0")

                val failure = assertFailsWith<IOException> {
                    prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("0.5.2"),
                        migrations = KodexHomeMigrations,
                        fileSystem = SystemCoroutineFileSystem,
                    ).closeAndJoin()
                }

                assertTrue(
                    targetRecord.toString() in failure.message.orEmpty(),
                    "Expected path $targetRecord in failure message: ${failure.message}",
                )
                assertEquals(invalidWorkPayload, SystemCoroutineFileSystem.readString(targetRecord))
                assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            }
        }

        for ((label, invalidUnstablePayload) in invalidUnstablePayloads()) {
            test("rejects $label in unstable timeline with pathful IOException and retains checkpoint") { home ->
                SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
                val session = createFullSessionSkeleton(home, 0)
                val unstable = timelineDirectory(session, "unstable")
                val targetRecord = recordPath(unstable, 0)
                SystemCoroutineFileSystem.writeString(targetRecord, invalidUnstablePayload)
                SystemCoroutineFileSystem.writeString(Path(unstable, "latest.json"), "0")

                val failure = assertFailsWith<IOException> {
                    prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("0.5.2"),
                        migrations = KodexHomeMigrations,
                        fileSystem = SystemCoroutineFileSystem,
                    ).closeAndJoin()
                }

                assertTrue(
                    targetRecord.toString() in failure.message.orEmpty(),
                    "Expected path $targetRecord in failure message: ${failure.message}",
                )
                assertEquals(invalidUnstablePayload, SystemCoroutineFileSystem.readString(targetRecord))
                assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            }
        }
    }
}

private fun invalidWorkPayloads(): List<Pair<String, String>> = listOf(
    "explicit null yield_time_ms in exec_command" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":null}},"result":{"type":"failure","message":"err"}}""",
    "explicit null yield_time_ms in write_stdin" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"write_stdin","arguments":{"session_id":1,"yield_time_ms":null}},"result":{"type":"failure","message":"err"}}""",
    "string yield_time_ms" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":"10000"}},"result":{"type":"failure","message":"err"}}""",
    "fractional yield_time_ms" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"write_stdin","arguments":{"session_id":1,"yield_time_ms":250.5}},"result":{"type":"failure","message":"err"}}""",
    "boolean yield_time_ms" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":true}},"result":{"type":"failure","message":"err"}}""",
    "object yield_time_ms" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":{}}},"result":{"type":"failure","message":"err"}}""",
    "overflow yield_time_ms" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":99999999999999999999999999999}},"result":{"type":"failure","message":"err"}}""",
    "missing action object" to
        """{"type":"command_execution_tool_event","call_id":"c1","result":{"type":"failure","message":"err"}}""",
    "unknown action type" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"run_shell","arguments":{"cmd":"ls"}},"result":{"type":"failure","message":"err"}}""",
    "non-object arguments" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":"{\"cmd\":\"ls\"}"},"result":{"type":"failure","message":"err"}}""",
    "exec_command missing cmd" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"exec_command","arguments":{"tty":false}},"result":{"type":"failure","message":"err"}}""",
    "write_stdin missing session_id" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"write_stdin","arguments":{"chars":""}},"result":{"type":"failure","message":"err"}}""",
    "write_stdin non-string chars" to
        """{"type":"command_execution_tool_event","call_id":"c1","action":{"type":"write_stdin","arguments":{"session_id":1,"chars":null}},"result":{"type":"failure","message":"err"}}""",
    "corrupted JSON" to
        """{"type":"command_execution_tool_event","call_id":""",
    "non-object work root" to
        """[]""",
)

private fun invalidUnstablePayloads(): List<Pair<String, String>> = listOf(
    "explicit null yield_time_ms in pending exec_command" to
        """[{"type":"command_execution","call_id":"p1","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":null}}}]""",
    "explicit null yield_time_ms in pending write_stdin" to
        """[{"type":"command_execution","call_id":"p1","action":{"type":"write_stdin","arguments":{"session_id":2,"yield_time_ms":null}}}]""",
    "string yield_time_ms in pending command" to
        """[{"type":"command_execution","call_id":"p1","action":{"type":"write_stdin","arguments":{"session_id":2,"yield_time_ms":"250"}}}]""",
    "unknown action type in pending command" to
        """[{"type":"command_execution","call_id":"p1","action":{"type":"other_action","arguments":{"cmd":"ls"}}}]""",
    "missing cmd in pending exec_command" to
        """[{"type":"command_execution","call_id":"p1","action":{"type":"exec_command","arguments":{}}}]""",
    "missing session_id in pending write_stdin" to
        """[{"type":"command_execution","call_id":"p1","action":{"type":"write_stdin","arguments":{"chars":"x"}}}]""",
    "corrupted unstable JSON" to
        """[{"type":"command_execution"""",
    "non-array unstable root" to
        """{"type":"command_execution"}""",
    "non-object unstable element" to
        """[123]""",
)

private class WriteTrackingFileSystem(
    private val writtenPaths: MutableList<Path>,
    private val trackOnlySessionRecords: Boolean = false,
) : CoroutineFileSystem by SystemCoroutineFileSystem {
    override suspend fun writeBytes(
        path: Path,
        content: ByteArray,
        append: Boolean,
        mustCreate: Boolean,
    ) {
        if (path.name != ".kodex-migration-0.5.2-record.tmp" &&
            (!trackOnlySessionRecords || (path.name.endsWith(".json") && path.name != "version.json" && path.name != "latest.json"))
        ) {
            writtenPaths += path
        }
        SystemCoroutineFileSystem.writeBytes(path, content, append, mustCreate)
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        SystemCoroutineFileSystem.atomicMove(source, destination)
        if (source.name == ".kodex-migration-0.5.2-record.tmp") {
            writtenPaths += destination
        }
    }
}

private fun migrationTemporary(timeline: Path): Path =
    Path(timeline, ".kodex-migration-0.5.2-record.tmp")

private suspend fun createFullSessionSkeleton(
    home: Path,
    index: Int,
    archived: Boolean = false,
): Path {
    val session = Path(home, "sessions", index.toString())
    listOf("index", "work", "settings", "timestamp", "token-count", "unstable").forEach { timelineName ->
        val timeline = timelineDirectory(session, timelineName)
        SystemCoroutineFileSystem.createDirectories(timeline)
        SystemCoroutineFileSystem.writeString(Path(timeline, "latest.json"), "-1")
    }
    SystemCoroutineFileSystem.writeString(Path(session, "index", "0.json"), """{"type":"compaction_point"}""")
    SystemCoroutineFileSystem.writeString(Path(session, "index", "latest.json"), "0")
    SystemCoroutineFileSystem.writeString(Path(session, "settings", "0.json"), """{"model":"gpt-5"}""")
    SystemCoroutineFileSystem.writeString(Path(session, "settings", "latest.json"), "0")
    SystemCoroutineFileSystem.writeString(Path(session, "timestamp", "0.json"), "\"2026-10-20T00:00:00Z\"")
    SystemCoroutineFileSystem.writeString(Path(session, "timestamp", "latest.json"), "0")
    SystemCoroutineFileSystem.writeString(Path(session, "token-count", "0.json"), """{"kind":"legacy","total_tokens":0}""")
    SystemCoroutineFileSystem.writeString(Path(session, "token-count", "latest.json"), "0")
    if (archived) {
        SystemCoroutineFileSystem.writeString(Path(session, "archive.mark"), "")
    }
    return session
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
