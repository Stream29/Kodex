package io.github.stream29.kodex.app.migration.v0_3_3

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

public val migrateToV0_3_3Test by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("registers the versioned Home migrations") {
            assertEquals(
                listOf(MigrationVersion("0.3.3"), MigrationVersion("0.3.5")),
                KodexHomeMigrations.map { migration -> migration.toVersion },
            )
        }

        test("removes legacy patch deltas and preserves unrelated data") { home ->
            val work = createWorkTimeline(home, 0)
            val legacyPatch = LegacyPatchEvent.encodeToByteArray()
            val slimPatch = SlimPatchEvent.encodeToByteArray()
            val unrelated = """{"type":"reasoning","item":{"id":"reasoning"}}""".encodeToByteArray()
            SystemCoroutineFileSystem.writeBytes(recordPath(work, 1), legacyPatch)
            SystemCoroutineFileSystem.writeBytes(recordPath(work, 2), slimPatch)
            SystemCoroutineFileSystem.writeBytes(recordPath(work, 3), unrelated)
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.3.2\"")
            val legacySubagent = Path(home, "sessions", "0", "subagents", "legacy.json")
            SystemCoroutineFileSystem.createDirectories(checkNotNull(legacySubagent.parent))
            SystemCoroutineFileSystem.writeString(legacySubagent, "legacy")
            val staleTemporary = migrationTemporaryPath(work, 1)
            SystemCoroutineFileSystem.writeString(staleTemporary, "stale")
            val unknown = Path(work, "user-owned.data")
            SystemCoroutineFileSystem.writeString(unknown, "unknown")

            prepareKodexHome(
                home = home,
                currentVersion = MigrationVersion("0.3.3"),
                migrations = KodexHomeMigrations,
                fileSystem = SystemCoroutineFileSystem,
            ).closeAndJoin()

            val migrated = MigrationJson.parseToJsonElement(
                SystemCoroutineFileSystem.readString(recordPath(work, 1)),
            ).jsonObject
            val applyResult = migrated.getValue("result")
                .jsonObject
                .getValue("apply_result")
                .jsonObject
            assertFalse("delta" in applyResult)
            assertEquals(
                setOf("affected_paths"),
                applyResult.keys,
            )
            assertContentEquals(slimPatch, SystemCoroutineFileSystem.readBytes(recordPath(work, 2)))
            assertContentEquals(unrelated, SystemCoroutineFileSystem.readBytes(recordPath(work, 3)))
            assertEquals("legacy", SystemCoroutineFileSystem.readString(legacySubagent))
            assertEquals("unknown", SystemCoroutineFileSystem.readString(unknown))
            assertFalse(SystemCoroutineFileSystem.exists(staleTemporary))
            assertEquals(
                "\"0.3.3\"",
                SystemCoroutineFileSystem.readString(Path(home, "version.json")),
            )
        }

        test("is idempotent after records reach the slim schema") { home ->
            val work = createWorkTimeline(home, 0)
            SystemCoroutineFileSystem.writeString(recordPath(work, 1), LegacyPatchEvent)

            migrateToV0_3_3(home, SystemCoroutineFileSystem)
            val first = SystemCoroutineFileSystem.readBytes(recordPath(work, 1))
            migrateToV0_3_3(home, SystemCoroutineFileSystem)

            assertContentEquals(first, SystemCoroutineFileSystem.readBytes(recordPath(work, 1)))
        }

        test("reclaims legacy snapshot bytes") { home ->
            val work = createWorkTimeline(home, 0)
            val sentinel = "target-only-large-content"
            val legacyPatch = LegacyPatchEvent.replace(
                "target-only-old-content",
                "$sentinel\\n".repeat(20_000),
            )
            SystemCoroutineFileSystem.writeString(recordPath(work, 1), legacyPatch)
            val before = checkNotNull(
                SystemCoroutineFileSystem.metadataOrNull(recordPath(work, 1)),
            ).size

            migrateToV0_3_3(home, SystemCoroutineFileSystem)

            val migrated = SystemCoroutineFileSystem.readString(recordPath(work, 1))
            val after = checkNotNull(
                SystemCoroutineFileSystem.metadataOrNull(recordPath(work, 1)),
            ).size
            assertFalse(sentinel in migrated)
            assertEquals(SlimLegacyPatchEvent, migrated)
            assertFalse(after * 100 >= before)
        }

        test("rejects malformed matching patch records") { home ->
            val work = createWorkTimeline(home, 0)
            SystemCoroutineFileSystem.writeString(
                recordPath(work, 1),
                """{"type":"patch_tool_event","call_id":"broken"}""",
            )

            assertFailsWith<IOException> {
                migrateToV0_3_3(home, SystemCoroutineFileSystem)
            }
        }
    }
}

private suspend fun createWorkTimeline(home: Path, sessionIndex: Int): Path {
    val work = Path(home, "sessions", sessionIndex.toString(), "work")
    SystemCoroutineFileSystem.createDirectories(work)
    return work
}

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-migration-0.3.3-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private val MigrationJson = Json

private const val LegacyPatchEvent: String =
    """{"type":"patch_tool_event","call_id":"patch","diff":{"patch":"patch","hunks":[]},"result":{"type":"success","apply_result":{"affected_paths":{"added":[],"modified":["large.txt"],"deleted":[]},"delta":{"changes":[{"path":"large.txt","change":{"type":"update","move_path":null,"old_content":"target-only-old-content","overwritten_move_content":null,"new_content":"target-only-new-content"}}],"exact":true}}}}"""

private const val SlimPatchEvent: String =
    """{"type":"patch_tool_event","call_id":"slim","diff":{"patch":"patch","hunks":[]},"result":{"type":"success","apply_result":{"affected_paths":{"added":[],"modified":["small.txt"],"deleted":[]}}}}"""

private const val SlimLegacyPatchEvent: String =
    """{"type":"patch_tool_event","call_id":"patch","diff":{"patch":"patch","hunks":[]},"result":{"type":"success","apply_result":{"affected_paths":{"added":[],"modified":["large.txt"],"deleted":[]}}}}"""
