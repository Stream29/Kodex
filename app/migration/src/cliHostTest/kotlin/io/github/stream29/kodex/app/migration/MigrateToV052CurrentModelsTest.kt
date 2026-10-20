package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.app.migration.v0_4_7.LegacySettingsFixture
import io.github.stream29.kodex.app.migration.v0_5_2.LegacyUnstableMultiEventArray
import io.github.stream29.kodex.app.migration.v0_5_2.LegacyWorkExecMissingYield
import io.github.stream29.kodex.app.migration.v0_5_2.LegacyWorkWriteEmptyCharsMissingYield
import io.github.stream29.kodex.app.migration.v0_5_2.LegacyWorkWriteNonEmptyCharsMissingYield
import io.github.stream29.kodex.app.migration.v0_5_2.PrettyWorkExecNegativeYield
import io.github.stream29.kodex.app.migration.v0_5_2.WorkInvalidRawExecToolCall
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.filesystemlease.FileSystemReadLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Current-model integration tests for the 0.5.2 Home migration outside the frozen
 * `v0_5_2` directory. Relies on the strict no-default `ExecCommandArguments` and
 * `WriteStdinArguments` DTOs introduced in P2.
 */
public val migrateToV052CurrentModelsTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.5.2-current-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        val home = this
        withContext(NonCancellable) {
            withTimeout(10.seconds) { deleteRecursively(home) }
        }
    } asParameterForEach {
        test("upgrades across all historical versions from unversioned baseline to 0.5.2 and reads work, unstable, and recovered pending events via FileSystemAgentStorage") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"), LegacySettingsFixture)

            // Active root session 0 with legacy 0.3.3 patch delta, 0.4.5 token count, and 0.5.2 work/unstable command events
            val session0 = createValidSessionLayout(home, 0)
            val work0 = timelineDirectory(session0, "work")
            val unstable0 = timelineDirectory(session0, "unstable")
            val tokenCount0 = timelineDirectory(session0, "token-count")

            SystemCoroutineFileSystem.writeString(recordPath(work0, 0), LegacyPatchWithDelta)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 1), LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 2), LegacyWorkWriteEmptyCharsMissingYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 3), PrettyWorkExecNegativeYield)
            SystemCoroutineFileSystem.writeString(recordPath(work0, 4), WorkInvalidRawExecToolCall)
            SystemCoroutineFileSystem.writeString(Path(work0, "latest.json"), "4")

            SystemCoroutineFileSystem.writeString(recordPath(tokenCount0, 0), "42")
            SystemCoroutineFileSystem.writeString(Path(tokenCount0, "latest.json"), "0")

            SystemCoroutineFileSystem.writeString(recordPath(unstable0, 0), LegacyUnstableMultiEventArray)
            SystemCoroutineFileSystem.writeString(Path(unstable0, "latest.json"), "0")

            // Archived root session 1 with archive.mark
            val session1 = createValidSessionLayout(home, 1, archived = true)
            val work1 = timelineDirectory(session1, "work")
            SystemCoroutineFileSystem.writeString(recordPath(work1, 0), LegacyWorkWriteNonEmptyCharsMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work1, "latest.json"), "0")

            val applied = mutableListOf<String>()
            val handle = prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.2"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
                onMigrationStarted = { _, toVersion -> applied += toVersion.toString() },
            )
            try {
                assertEquals(MigrationVersion("0.5.2"), handle.version)
                assertEquals(
                    listOf("0.3.3", "0.3.5", "0.4.3", "0.4.5", "0.4.7", "0.4.15", "0.5.2"),
                    applied,
                )
                assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))

                val storage0 = FileSystemAgentStorage(session0, SystemCoroutineFileSystem)
                val storage1 = FileSystemAgentStorage(session1, SystemCoroutineFileSystem)

                // Verify 0.3.3 and 0.4.5 outputs are readable alongside 0.5.2
                assertIs<StablePatchToolEvent>(storage0.work[0])
                assertEquals(
                    TokenCountSnapshot(kind = TokenCountKind.Legacy, totalTokens = 42L),
                    storage0.tokenCount[0],
                )

                // Verify migrated work exec_command event and FunctionCall round-trip
                val execEvent = assertIs<StableCommandExecutionToolEvent>(storage0.work[1])
                assertEquals(
                    StableCommandExecutionToolEvent(
                        callId = "call-exec-1",
                        itemId = ResponseItemId("item-exec-1"),
                        action = StableCommandExecutionAction.ExecCommand(
                            ExecCommandArguments(
                                command = "echo hello",
                                workdir = "/workspace",
                                tty = false,
                                yieldTimeMillis = 10_000L,
                                maxOutputTokens = 10_000L,
                            ),
                        ),
                        result = StableCommandExecutionResult.Output(
                            UnifiedExecOutput(
                                chunkId = "chunk-1",
                                wallTimeSeconds = 0.12,
                                exitCode = 0,
                                originalTokenCount = 2,
                                output = "hello\n",
                            ),
                        ),
                    ),
                    execEvent,
                )
                val projectedCall = assertIs<ResponseItem.FunctionCall>(execEvent.toResponseHistoryItems().first())
                assertEquals("exec_command", projectedCall.name)
                assertEquals(
                    ExecCommandArguments(
                        command = "echo hello",
                        workdir = "/workspace",
                        tty = false,
                        yieldTimeMillis = 10_000L,
                        maxOutputTokens = 10_000L,
                    ),
                    OpenAiJsonCodec.decodeFromString(ExecCommandArguments.serializer(), projectedCall.arguments),
                )

                // Verify migrated work write_stdin (empty poll) event
                val writePollEvent = assertIs<StableCommandExecutionToolEvent>(storage0.work[2])
                assertEquals(
                    StableCommandExecutionAction.WriteStdin(
                        WriteStdinArguments(
                            sessionId = 7,
                            chars = "",
                            yieldTimeMillis = 250L,
                            maxOutputTokens = 10_000L,
                        ),
                    ),
                    writePollEvent.action,
                )

                // Verify negative yield_time_ms is preserved on historical failed work event
                val negativeExecEvent = assertIs<StableCommandExecutionToolEvent>(storage0.work[3])
                assertEquals(
                    StableCommandExecutionAction.ExecCommand(
                        ExecCommandArguments(
                            command = "sleep 1",
                            tty = false,
                            yieldTimeMillis = -1L,
                            maxOutputTokens = 10_000L,
                        ),
                    ),
                    negativeExecEvent.action,
                )

                // Verify raw invalid tool call remains untouched
                val invalidWorkEvent = assertIs<StableInvalidToolCall>(storage0.work[4])
                assertEquals(
                    InvalidToolInvocation.Function(
                        name = "exec_command",
                        arguments = "{\"cmd\":\"echo raw\"}",
                    ),
                    invalidWorkEvent.invocation,
                )

                // Verify archived session 1 was also migrated and can be read by current reader
                val archivedWriteEvent = assertIs<StableCommandExecutionToolEvent>(storage1.work[0])
                assertEquals(
                    StableCommandExecutionAction.WriteStdin(
                        WriteStdinArguments(
                            sessionId = 7,
                            chars = "y\n",
                            yieldTimeMillis = 250L,
                            maxOutputTokens = 4096L,
                        ),
                    ),
                    archivedWriteEvent.action,
                )

                // Verify unstable array reads cleanly and pending recovery preserves migrated yields
                val unstableEvents = storage0.unstable[0]
                assertEquals(7, unstableEvents.size)
                val pendingExec = assertIs<PendingCommandExecutionToolEvent>(unstableEvents[0])
                assertEquals(
                    PendingCommandExecutionAction.ExecCommand(
                        ExecCommandArguments(
                            command = "./gradlew check",
                            tty = true,
                            yieldTimeMillis = 10_000L,
                            maxOutputTokens = 10_000L,
                        ),
                    ),
                    pendingExec.action,
                )
                val pendingPoll = assertIs<PendingCommandExecutionToolEvent>(unstableEvents[1])
                assertEquals(
                    PendingCommandExecutionAction.WriteStdin(
                        WriteStdinArguments(
                            sessionId = 11,
                            chars = "",
                            yieldTimeMillis = 250L,
                            maxOutputTokens = 10_000L,
                        ),
                    ),
                    pendingPoll.action,
                )
                val pendingWrite = assertIs<PendingCommandExecutionToolEvent>(unstableEvents[2])
                assertEquals(
                    PendingCommandExecutionAction.WriteStdin(
                        WriteStdinArguments(
                            sessionId = 11,
                            chars = "quit\n",
                            yieldTimeMillis = 250L,
                            maxOutputTokens = 10_000L,
                        ),
                    ),
                    pendingWrite.action,
                )
                val pendingNeg = assertIs<PendingCommandExecutionToolEvent>(unstableEvents[3])
                assertEquals(
                    -250L,
                    assertIs<PendingCommandExecutionAction.WriteStdin>(pendingNeg.action).arguments.yieldTimeMillis,
                )
                val pendingHuge = assertIs<PendingCommandExecutionToolEvent>(unstableEvents[4])
                assertEquals(
                    900_000L,
                    assertIs<PendingCommandExecutionAction.ExecCommand>(pendingHuge.action).arguments.yieldTimeMillis,
                )
                assertIs<PendingFunctionToolEvent>(unstableEvents[5])
                val pendingInvalid = assertIs<PendingInvalidToolCall>(unstableEvents[6])
                assertEquals(
                    PendingInvalidToolInvocation.Function(
                        name = "write_stdin",
                        arguments = "{\"session_id\":11}",
                    ),
                    pendingInvalid.invocation,
                )

                // Recover pending events to failed work events and persist them through FileSystemAgentStorage
                val recoveredExec = assertIs<StableCommandExecutionToolEvent>(
                    (pendingExec as PendingToolEvent).toFailedToolEvent("Interrupted by restart"),
                )
                val recoveredPoll = assertIs<StableCommandExecutionToolEvent>(
                    (pendingPoll as PendingToolEvent).toFailedToolEvent("Interrupted by restart"),
                )
                storage0.work[5] = recoveredExec
                storage0.work[6] = recoveredPoll
                assertEquals(recoveredExec, storage0.work[5])
                assertEquals(recoveredPoll, storage0.work[6])
            } finally {
                handle.closeAndJoin()
            }
        }

        test("future entry 0.5.2 does not run at 0.5.1, strict reader rejects unmigrated records, and activating 0.5.2 makes them readable") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
            val session = createValidSessionLayout(home, 0)
            val work = timelineDirectory(session, "work")
            val unstable = timelineDirectory(session, "unstable")
            SystemCoroutineFileSystem.writeString(recordPath(work, 0), LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "0")
            SystemCoroutineFileSystem.writeString(recordPath(unstable, 0), LegacyUnstableMultiEventArray)
            SystemCoroutineFileSystem.writeString(Path(unstable, "latest.json"), "0")

            var migrationStartedAt051 = false
            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.1"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
                onMigrationStarted = { _, _ -> migrationStartedAt051 = true },
            ).closeAndJoin()

            assertFalse(migrationStartedAt051)
            assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))

            // Unmigrated records missing yield_time_ms must be rejected by the strict current reader
            val unmigratedStorage = FileSystemAgentStorage(session, SystemCoroutineFileSystem)
            assertFailsWith<SerializationException> {
                unmigratedStorage.work[0]
            }
            assertFailsWith<SerializationException> {
                unmigratedStorage.unstable[0]
            }
            assertFailsWith<SerializationException> {
                OpenAiJsonCodec.decodeFromString(StableWorkEvent.serializer(), LegacyWorkWriteEmptyCharsMissingYield)
            }
            assertFailsWith<SerializationException> {
                OpenAiJsonCodec.decodeFromString(
                    ListSerializer(UnstableCleanEvent.serializer()),
                    LegacyUnstableMultiEventArray,
                )
            }

            // Activating 0.5.2 runs the migration and allows the strict current reader to open the records
            val activated = mutableListOf<MigrationVersion>()
            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.2"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
                onMigrationStarted = { _, toVersion -> activated += toVersion },
            ).closeAndJoin()

            assertEquals(listOf(MigrationVersion("0.5.2")), activated)
            assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))

            val migratedStorage = FileSystemAgentStorage(session, SystemCoroutineFileSystem)
            val workEvent = assertIs<StableCommandExecutionToolEvent>(migratedStorage.work[0])
            assertEquals(
                10_000L,
                assertIs<StableCommandExecutionAction.ExecCommand>(workEvent.action).arguments.yieldTimeMillis,
            )
            val unstableList = migratedStorage.unstable[0]
            assertEquals(
                10_000L,
                assertIs<PendingCommandExecutionAction.ExecCommand>(
                    assertIs<PendingCommandExecutionToolEvent>(unstableList[0]).action,
                ).arguments.yieldTimeMillis,
            )
        }

        test("does not scan Session data or acquire a write lease when already at 0.5.2") { home ->
            val versionPath = Path(home, "version.json")
            SystemCoroutineFileSystem.writeString(versionPath, "\"0.5.2\"")
            SystemCoroutineFileSystem.writeString(Path(home, "sessions"), "not-a-directory")
            val guardedFs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writeBytes(
                    path: Path,
                    content: ByteArray,
                    append: Boolean,
                    mustCreate: Boolean,
                ) {
                    check(path != versionPath && !path.name.endsWith(".write.lock")) {
                        "Home already at 0.5.2 must not acquire a writer or rewrite version.json: $path"
                    }
                    SystemCoroutineFileSystem.writeBytes(path, content, append, mustCreate)
                }
            }
            var migrationStarted = false
            val handle = prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.2"),
                migrations = KodexHomeMigrations,
                fileSystem = guardedFs,
                onMigrationStarted = { _, _ -> migrationStarted = true },
            )
            try {
                assertEquals(MigrationVersion("0.5.2"), handle.version)
                assertFalse(migrationStarted)
                assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(versionPath))
            } finally {
                handle.closeAndJoin()
            }
        }

        test("reports exclusive lease conflict when upgrading from 0.4.15 to 0.5.2 and succeeds after reader closes") { home ->
            val versionPath = Path(home, "version.json")
            SystemCoroutineFileSystem.writeString(versionPath, "\"0.4.15\"")
            val session = createValidSessionLayout(home, 0)
            val work = timelineDirectory(session, "work")
            SystemCoroutineFileSystem.writeString(recordPath(work, 0), LegacyWorkExecMissingYield)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "0")

            val locks = Path(home, ".locks", "home")
            val reader = FileSystemReadLease(locks)
            var migrationStarted = false
            try {
                val failure = assertFailsWith<KodexHomeInUseException> {
                    prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("0.5.2"),
                        migrations = KodexHomeMigrations,
                        fileSystem = SystemCoroutineFileSystem,
                        onMigrationStarted = { _, _ -> migrationStarted = true },
                    )
                }
                val message = failure.message.orEmpty()
                assertTrue("needs data migration from 0.4.15 to 0.5.2" in message)
                assertTrue("Required data migration targets: 0.5.2." in message)
                assertTrue(failure.cause is FileSystemLeaseInUseException)
                assertFalse(migrationStarted)
                assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(versionPath))
                assertEquals(LegacyWorkExecMissingYield, SystemCoroutineFileSystem.readString(recordPath(work, 0)))
            } finally {
                reader.closeAndJoin()
            }

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.5.2"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
                onMigrationStarted = { _, _ -> migrationStarted = true },
            ).closeAndJoin()

            assertTrue(migrationStarted)
            assertEquals("\"0.5.2\"", SystemCoroutineFileSystem.readString(versionPath))
            val storage = FileSystemAgentStorage(session, SystemCoroutineFileSystem)
            assertEquals(
                10_000L,
                assertIs<StableCommandExecutionAction.ExecCommand>(
                    assertIs<StableCommandExecutionToolEvent>(storage.work[0]).action,
                ).arguments.yieldTimeMillis,
            )
        }

        test("retains 0.4.15 checkpoint when 0.5.2 migration fails on bad command record") { home ->
            val versionPath = Path(home, "version.json")
            SystemCoroutineFileSystem.writeString(versionPath, "\"0.4.15\"")
            val session = createValidSessionLayout(home, 0)
            val work = timelineDirectory(session, "work")
            val badRecord = recordPath(work, 0)
            val badPayload =
                """{"type":"command_execution_tool_event","call_id":"bad","action":{"type":"exec_command","arguments":{"cmd":"ls","yield_time_ms":null}},"result":{"type":"failure","message":"err"}}"""
            SystemCoroutineFileSystem.writeString(badRecord, badPayload)
            SystemCoroutineFileSystem.writeString(Path(work, "latest.json"), "0")

            val failure = assertFailsWith<IOException> {
                prepareKodexHome(
                    home = home,
                    currentVersion = MigrationVersion("0.5.2"),
                    migrations = KodexHomeMigrations,
                    fileSystem = SystemCoroutineFileSystem,
                )
            }
            assertTrue(badRecord.toString() in failure.message.orEmpty())
            assertEquals("\"0.4.15\"", SystemCoroutineFileSystem.readString(versionPath))
            assertEquals(badPayload, SystemCoroutineFileSystem.readString(badRecord))
            assertTrue(SystemCoroutineFileSystem.list(Path(home, ".locks", "home")).isEmpty())
        }
    }
}

private const val LegacyPatchWithDelta: String =
    """{"type":"patch_tool_event","call_id":"patch","diff":{"patch":"patch","hunks":[]},"result":{"type":"success","apply_result":{"affected_paths":{"added":[],"modified":["file.txt"],"deleted":[]},"delta":{"changes":[],"exact":true}}}}"""

private suspend fun createValidSessionLayout(
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
